# Thundra Lambda Warmup

Warmup support for AWS Lambda functions to prevent cold starts as much as possible.

## Setup

Take the following steps to setup warmup support: 
* Thundra provides a Lambda function called `thundra-lambda-warmup` on the user end to trigger Lambda functions with warmup messages periodically. This function must be deployed once.
* Specify the Lambda functions to be warmed-up. 

The following ways are supported to setup `thundra-lambda-warmup` on user end:
### Manual Setup
If the user end is not an automated deployment environment, setup `thundra-lambda-warmup` manually on user end by CloudFormation template. See [here](https://docs.thundra.io/docs/warmup-manual-setup) for the details.
### Serverless Framework
Deploys `thundra-lambda-warmup` by using [Serverless framework](https://serverless.com/framework/). If a Serverless framework is already used for deploying your Lambda functions, this is the *recommended* option with the least number of manual actions. See [here](https://docs.thundra.io/docs/warmup-serverless-framework) for the details.

## How to Use

After setup you need to specify the Lambda functions to be warmed-up as explained [here](https://docs.thundra.io/docs/warmup-configuration).

Thundra agents come with warmup support out-of-the-box. So you don't need extra action except Java agent for a particular case: https://docs.thundra.io/docs/warmup-configuration#section-java-agent-specific-configurations

If you don't use Thundra agents on your Lambda function, you need to handle warmup messages yourself as shown below:

### Java

Sample request and response:
``` java
public class MyAwesomeRequest {

    private String requestId;
    private String description;

    // ...

    // Getters and setters ...
    
}    

public class MyAwesomeResponse {

    private String responseId;

    // ...

    // Getters and setters ...
    
} 
```

#### RequestHandler implementation

``` java
public class MyAwesomeRequestHandler
        implements RequestHandler<MyAwesomeRequest, MyAwesomeResponse> {

    private boolean checkAndHandleWarmupRequest(MyAwesomeRequest request, Context context) {
        // Check whether it is empty request which is used as default warmup request
        if (StringUtils.isNullOrEmpty(request.getRequestId())
            && 
            StringUtils.isNullOrEmpty(request.getDescription())) {
            context.getLogger().log("Received warmup request as empty message. " +
                                    "Handling with 100 milliseconds delay ...\n");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
            return true;
        }
        return false;
    }

    @Override
    public MyAwesomeResponse handleRequest(MyAwesomeRequest request, Context context) {
        if (!checkAndHandleWarmupRequest(request, context)) {
            return doHandleRequest(request, context);
        } else {
            return null;
        }
    }

    private MyAwesomeResponse doHandleRequest(MyAwesomeRequest request, Context context) {
        // TODO My awesome logic

        return new MyAwesomeResponse().setResponseId(UUID.randomUUID().toString());
    }

}
```

#### RequestStreamHandler implementation

``` java
public class MyAwesomeRequestStreamHandler implements RequestStreamHandler {

    private static final Pattern WARMUP_PATTERN = Pattern.compile("#warmup (wait=(\\d+))?");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private boolean checkAndHandleWarmupRequest(InputStream input, Context context) throws IOException {
        // Check whether it is empty request which is used as default warmup request
        if (input.available() <= 3) {
            context.getLogger().log("Received warmup request as empty message. " +
                                    "Handling with 100 milliseconds delay ...\n");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
            return true;
        } else {
            long delayTime = 100;
            Scanner scanner = new Scanner(input);
            String match = scanner.findWithinHorizon(WARMUP_PATTERN, 0);
            // Check whether it is warmup request
            if (match != null) {
                MatchResult matchResult = scanner.match();
                // Check whether "wait" argument is provided
                // to specify extra wait time before returning from request
                if (matchResult.groupCount() == 2) {
                    long waitTime = Long.parseLong(matchResult.group(2));
                    delayTime += waitTime;
                }
                context.getLogger().log("Received warmup request as warmup message. " +
                                        "Handling with " + delayTime + " milliseconds delay ...\n");
                try {
                    Thread.sleep(delayTime);
                } catch (InterruptedException e) {
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        BufferedInputStream bis = new BufferedInputStream(input);
        bis.mark(Integer.MAX_VALUE);
        if (!checkAndHandleWarmupRequest(bis, context)) {
            bis.reset();
            MyAwesomeRequest request = objectMapper.readValue(bis, MyAwesomeRequest.class);
            MyAwesomeResponse response = doHandleRequest(request, context);
            objectMapper.writeValue(output, response);
        }
    }

    private MyAwesomeResponse doHandleRequest(MyAwesomeRequest request, Context context) {
        // TODO My awesome logic

        return new MyAwesomeResponse().setResponseId(UUID.randomUUID().toString());
    }

}
```

### NodeJS
#### 1) With [thundra-lambda-nodejs-warmup](https://github.com/thundra-io/thundra-lambda-nodejs-warmup):

```bash
npm install @thundra/warmup --save
```

```js
const thundraWarmup = require("@thundra/warmup");

const thundraWarmupWrapper = thundraWarmup();

exports.handler = thundraWarmupWrapper((event, context, callback) => {
    callback(null, "No more cold starts!");
});
```

You can also pass an optional callback function which will be called on warmup requests.

```js
const thundraWarmup = require("@thundra/warmup");

const optionalCallback = () => console.log(Warming up...);

const thundraWarmupWrapper = thundraWarmup(optionalCallback);

exports.handler = thundraWarmupWrapper((event, context, callback) => {
    callback(null, "No more cold starts!");
});
```
#### 2) Manually 
``` javascript
function checkAndHandleWarmupRequest(event, callback) {
    // Check whether it is empty request which is used as default warmup request
    if (Object.keys(event).length === 0) {
        console.log("Received warmup request as empty message. " + 
                    "Handling with 100 milliseconds delay ...");
        setTimeout(function() {
            callback(null);
        }, 100);
        return true;
    } else {
        var isString = (typeof event === 'string' || event instanceof String);
        if (isString) {
            // Check whether it is warmup request 
            if (event.startsWith('#warmup')) {
                var delayTime = 100;
                var args = event.substring('#warmup'.length).trim().split(/\s+/);
                // Iterate over all warmup arguments
                for (let arg of args) {
                    var argParts = arg.split('=');
                    // Check whether argument is in key=value format
                    if (argParts.length == 2) {
                        var argName = argParts[0];
                        var argValue = argParts[1];
                        // Check whether argument is "wait" argument 
                        // which specifies extra wait time before returning from request
                        if (argName === 'wait') {
                            var waitTime = parseInt(argValue);
                            delayTime += waitTime;
                        }
                    }
                }
                console.log("Received warmup request as warmup message. " + 
                            "Handling with " + delayTime + " milliseconds delay ...");
                setTimeout(function() {
                    callback(null);
                }, delayTime);
                return true;
            }
       } 
       return false;
    }   
}

exports.handler = (event, context, callback) => {
    // Check whether it is warmup request
    // Handle warmup request if it is warmup message
    if (!checkAndHandleWarmupRequest(event, callback)) {
        // TODO implement
        callback(null, 'Hello from Lambda');
    }      
};
```

### Python

``` python
import time

def checkAndHandleWarmupRequest(event):
    # Check whether it is empty request which is used as default warmup request
    if (not event):
        print("Received warmup request as empty message. " + 
              "Handling with 100 milliseconds delay ...")
        time.sleep(0.1)
        return True
    else:
        if (isinstance(event, str)):
            # Check whether it is warmup request 
            if (event.startswith('#warmup')):
                delayTime = 100
                args = event[len('#warmup'):].strip().split()
                # Warmup messages are in '#warmup wait=<waitTime>' format
                # Iterate over all warmup arguments
                for arg in args:
                    argParts = arg.split('=')
                    # Check whether argument is in key=value format
                    if (len(argParts) == 2):
                        argName = argParts[0]
                        argValue = argParts[1]
                        # Check whether argument is "wait" argument 
                        # which specifies extra wait time before returning from request
                        if (argName == 'wait'):
                            waitTime = int(argValue)
                            delayTime += waitTime
                print("Received warmup request as warmup message. " + 
                      "Handling with " + str(delayTime) + " milliseconds delay ...")  
                time.sleep(delayTime / 1000)      
                return True
        return False

def lambda_handler(event, context):
    if checkAndHandleWarmupRequest(event):
        return None
    else:    
        # TODO implement
        return 'Hello from Lambda'
```

### Go

You can use [thundra-lambda-agent-go](https://github.com/thundra-io/thundra-lambda-agent-go) if you don't want to
implement warmup handling by yourself.

If you don't want to use the agent, keep on reading.
#### Handler With Event
If your lambda handler expects to receive a struct you can use the following implementation.

Note that this version doesn't allow you to send configurable requests from thundra-lambda-warmup because it is 
designed to send string typed stream messages.
``` go
func checkAndHandleWarmupRequest(event MyEvent) bool {
    if event == (MyEvent{}) {
        fmt.Println("Received warmup request as empty message. Handling with 100 milliseconds delay ...")
        time.Sleep(time.Millisecond * 100)
        return true
    }
    return false
}

// This is your lambda function
func HandleLambdaEvent(ctx context.Context, event MyEvent) (MyResponse, error) {
    if checkAndHandleWarmupRequest(event) {
        // Return empty or dummy response on warmup request
        return MyResponse{}, nil
    } else {
        // TODO implement
        return MyResponse{Message:"Hello from Thundra"},nil
    }
}
```

#### Handler With Stream
If your lambda handler expects to receive a string typed stream data then you can use the following implementation.

``` go
func checkAndHandleWarmupRequest(event string) bool {
    if event == "" {
        fmt.Println("Received warmup request as empty message. Handling with 100 milliseconds delay ...")
        time.Sleep(time.Millisecond * 100)
        return true
    }

    // Check whether it is warmup request
    if strings.HasPrefix(event, "#warmup") {
        delay := 100

        // Warmup data has the following format "#warmup wait=200 k1=v1"
        //Therefore we need to parse it to only have arguments in key=value format
        sp := strings.SplitAfter(event, "#warmup")[1]
        args := strings.Fields(sp)
        // Iterate over all warmup arguments
        for _, a := range args {
            argParts := strings.Split(a, "=")
            // Check whether argument is in key=value format
            if len(argParts) == 2 {
                k := argParts[0]
                v := argParts[1]
                // Check whether argument is "wait" argument
                // which specifies extra wait time before returning from request
                if k == "wait" {
                    w, err := strconv.Atoi(v)
                    if err != nil {
                        fmt.Println(err)
                    } else {
                        delay += w
                    }
                }
            }
        }
        fmt.Println("Received warmup request as warmup message. Handling with ", delay, " milliseconds delay ...")
        time.Sleep(time.Millisecond * time.Duration(delay))
        return true;
    }

    return false
}

// This is your lambda function
func HandleLambdaEvent(ctx context.Context, event string) (MyResponse, error) {
    if checkAndHandleWarmupRequest(event) {
        // Return empty or dummy response on warmup request
        return MyResponse{}, nil
    } else {
        // TODO implement
        return MyResponse{Message:"Hello from Thundra"},nil
    }
}
```

## The API

### WarmupHandler

`io.thundra.lambda.warmup.WarmupHandler` is the AWS Lambda `RequestHandler` implementation which triggers warmup action through `io.thundra.lambda.warmup.strategy.WarmupStrategy`s for configured/discovered functions.

This handler needs some permissions to do its job.
* `lambda:InvokeFunction`: This permission is needed for invoking functions to warmup.
* `lambda:ListAliases`: This permission is needed when the alias discovery is used (enabled by default) for invoking functions by using alias as qualifier to warmup.
* `lambda:ListFunctions`: This permission is needed when any configuration discovery is used (enabled by default) for retrieving configurations of functions to warmup.

### WarmupStrategy

`io.thundra.lambda.warmup.strategy.WarmupStrategy` is the interface for implementations which execute warmup action for the given AWS Lambda functions.

#### StandardWarmupStrategy

`io.thundra.lambda.warmup.strategy.impl.StandardWarmupStrategy` is the standard `io.thundra.lambda.warmup.strategy.WarmupStrategy` implementation which warms-up incrementally as randomized invocation counts for preventing full load (all containers are busy with warmup invocations) on AWS Lambda during warmup to leave some AWS Lambda containers free/available for real requests and simulating real environment as much as possible. Name of this strategy is `standard`.

This strategy invokes with empty warmup messages if no invocation data is specified by `io.thundra.lambda.warmup.invocationData`. Therefore, the target Lambda functions to warmup must handle empty messages. By default it is suggested to wait `100 milliseconds` for warmup requests before return. This is needed for keeping multiple Lambda containers up. The reason is that when there is no delay, the invoked Lambda container does its job quickly and becomes available to be reused in a very short time. So it is expected that multiple warmup invocations are dispatched to the same Lambda container instead of another one. By waiting before return, warmup request keep Lambda container busy and therefore, possibly the other warmup requests are routed to another containers even create new one if there is no available one. If the concurrent warmup invocation count increases, wait time at target Lambda function side should be increased accordingly as well. Because delay time at target Lambda function side might be insufficient for the required time high number of concurrent warmup invocations to keep containers busy in the meantime. For every `10` concurrent invocation, `100 milliseconds` wait time is reasonable by our experiments.

#### StatAwareWarmupStrategy

`io.thundra.lambda.warmup.strategy.impl.StatAwareWarmupStrategy` is the `io.thundra.lambda.warmup.strategy.WarmupStrategy` implementation which takes Lambda stats into consideration while warming-up. If the target Lambda function is hot (invoked frequently), it is aimed to keep more instance of that Lambda function up by warmup it with more concurrent invocation. Name of this strategy is `stat-aware`. 

This strategy invokes with warmup message in `#warmup wait=<wait_time>` format. In here `<wait_time>` is the additional delay time for the invoked target Lambda functions to wait before return. In here the strategy itself calculates `<wait_time>` by adding **extra** `100 milliseconds` for every **extra** `10` concurrent warmup invocation count. So, it suggested to wait `100 + <wait_time> milliseconds` for warmup requests at the target Lambda function side.

As mentioned above, this strategy can be also be configured to be smart enough to scale up/down warmup invocation counts according to target Lambda function usage stats. By this feature, hot functions are invoked with higher concurrent warmup invocation count by automatically increasing invocation count from standard/defined invocation count. The opposite logic is valid of cold functions by warming-up them lower concurrent warmup invocation count by automatically decreasing invocation count. To take advantage of this feature (note that this feature is optional and disabled by default, so in case of empty/null return value, auto scale feature is not used and goes on with standard invocation count as in `io.thundra.lambda.warmup.strategy.impl.StandardWarmupStrategy`), target Lambda function should return an `instanceId` unique to the Lambda handler instance (ex. a random generated UUID for the Lambda handler instance) and `latestRequestTime` which represents the latest request (not empty/warmup message) time in `yyyy-MM-dd HH:mm:ss.SSS` format as JSON like below:
``` json
{
  "instanceId": "9b3ba0d0-d515-4a21-b3ee-133a321d9dbe",
  "latestRequestTime": "2017-07-30 17:26:27.778"
}
```

#### StrategyAwareWarmupStrategy

`io.thundra.lambda.warmup.strategy.impl.StrategyAwareWarmupStrategy` is the `io.thundra.lambda.warmup.strategy.WarmupStrategy` implementation which takes configured/specified `io.thundra.lambda.warmup.strategy.WarmupStrategy`s for functions into consideration while warming-up. Name of this strategy is `strategy-aware`. If there is no configured/specified `io.thundra.lambda.warmup.strategy.WarmupStrategy`s, uses given `io.thundra.lambda.warmup.strategy.WarmupStrategy` by default. 

## Configuration

### Configurations of WarmupHandler

- `thundra_lambda_warmup_function`: `String` typed property prefix that declares functions to warmup and their configurations. Multiple functions and their configurations can be specified with this prefix such as `thundra_lambda_warmup_function1`, `thundra_lambda_warmup_function2`, ... Additionally, in a single `thundra_lambda_warmup_function` environment variable, multiple functions and their configurations can be specified as comma (`,`) separated (For example, environment variable name is `thundra_lambda_warmup_function` and value is `my-func-1,my-func-2,my-func-3`). Besides function definition, configuration specification is also supported as key-value after function definition. This property is used in `thundra_lambda_warmup_function...[conf1=val1;conf2=val2;...]` format by appending configurations in key-value (separated by `=`) after function definition between `[` and `]` characters and separating each of them by `;` character. Note that configuration part is optional. The following configurations are supported through this property:
  - `alias`: Configures alias to be used as qualifier while invoking the defined functions with warmup request.
  - `warmupStrategy`: Configures name of the `io.thundra.lambda.warmup.strategy.WarmupStrategy` implementation to be used while warming-up the defined function.
  - `invocationCount`: Configures concurrent invocation count for the defined function to warmup.
  - `invocationData`: Configures invocation data to be used as invocation request while warming-up the defined function. By default empty message is used.
- `thundra_lambda_warmup_disableAllDiscoveries`: `Boolean` typed property that disables discovery mechanism for all configurations. Default value is `false`.
- `thundra_lambda_warmup_warmupAware`: Name of the `Boolean` typed environment variable to be used for discovering Lambda functions to warmup. If a Lambda function wants to be warmed-up, it can publish itself by having this environment variable as enabled (`true`). Then, this handler will assume that this Lambda function want to be warmed-up and will add it to its function list to warmup. This configuration is specified at the Lambda function to be warmed-up (**NOT** at `thundra-lambda-warmup` Lambda function).
- `thundra_lambda_warmup_disableWarmupAwareDiscovery`: `Boolean` typed property that disables discovery mechanism for warmup aware functions specified by `io.thundra.lambda.warmup.WarmupHandler#WARMUP_AWARE_ENV_VAR_NAME`. Default value is `false`.
- `thundra_lambda_warmup_warmupGroupName`: `String` typed property that configures group name of this handler. If warmup group name is specified by this property for this handler, this handler only discovers and warms-up Lambda functions in the same warmup group (having same warmup group name specified by `io.thundra.lambda.warmup.WarmupHandler#WARMUP_GROUP_NAME_ENV_VAR_NAME`). This configuration is specified at the Lambda function to be warmed-up (**NOT** at `thundra-lambda-warmup` Lambda function).
- `thundra_lambda_warmup_groupName`: `String` typed property that configures group name of this handler. If warmup group name is specified by this property for this handler, this handler only discovers and warms-up Lambda functions in the same warmup group (having same warmup group name specified by `io.thundra.lambda.warmup.WarmupHandler#WARMUP_GROUP_NAME_ENV_VAR_NAME`).
- `thundra_lambda_warmup_strategy`: `String` typed property that configures name of the `io.thundra.lambda.warmup.strategy.WarmupStrategy` implementation to be used. Default value is the name of the `io.thundra.lambda.warmup.strategy.impl.StrategyAwareWarmupStrategy`.
- `thundra_lambda_warmup_warmupStrategy`: `String` typed environment variable to be used for discovering specific warmup strategy name configuration of Lambda functions to warmup. This configuration is specified at the Lambda function to be warmed-up (**NOT** at `thundra-lambda-warmup` Lambda function).
- `thundra_lambda_warmup_disableWarmupStrategyDiscovery`: `Boolean` typed property that disables discovery mechanism for warmup strategy name configurations specified by `io.thundra.lambda.warmup.WarmupHandler#WARMUP_STRATEGY_ENV_VAR_NAME`. Default value is `false`.
- `thundra_lambda_warmup_invocationData`: `String` typed property that configures invocation data to be used as invocation request while warming-up. By default empty message is used.
- `thundra_lambda_warmup_warmupInvocationData`: `String` typed environment variable to be used for discovering specific warmup invocation data configuration of Lambda functions to warmup. This configuration is specified at the Lambda function to be warmed-up (**NOT** at `thundra-lambda-warmup` Lambda function).
- `thundra_lambda_warmup_disableWarmupInvocationDataDiscovery`: `Boolean` typed property that disables discovery mechanism for warmup invocation data configurations specified by `io.thundra.lambda.warmup.WarmupHandler#INVOCATION_DATA_ENV_VAR_NAME`. Default value is `false`.
- `thundra_lambda_warmup_warmupInvocationCount`: `Integer` typed environment variable to be used for discovering specific warmup invocation count configuration of Lambda functions to warmup. This configuration is specified at the Lambda function to be warmed-up (**NOT** at `thundra-lambda-warmup` Lambda function).
- `thundra_lambda_warmup_disableWarmupInvocationCountDiscovery`: `Boolean` typed property that disables discovery mechanism for warmup invocation count configurations specified by `io.thundra.lambda.warmup.WarmupHandler#INVOCATION_COUNT_ENV_VAR_NAME`.
- `thundra_lambda_warmup_disableAliasDiscovery`: `Boolean` typed property that disables alias discovery mechanism to be used as qualifier while invoking Lambda functions to warmup. When alias discovery mechanism is active (active by default), alias with the latest version number is used as qualifier on invocation. Default value is `false`.

### Configurations of StandardWarmupStrategy

- `thundra_lambda_warmup_invocationCount`: `Integer` typed property that configures the invocation count for each Lambda function to warmup. Note that if invocation counts are randomized, this value is used as upper limit of randomly generated invocation count. Default value is `8`.
- `thundra_lambda_warmup_invocationResultConsumerCount`: `Integer` typed property that configures the count of consumers to get results of warmup invocations. The default value is two times of available CPU processors.
- `thundra_lambda_warmup_iterationCount`: `Integer` typed property that configures the warmup iteration count. Default value is `2`.
- `thundra_lambda_warmup_enableSplitIterations`: `Boolean` typed property that enables splitting iterations between multiple schedules of this handler and at each schedule call only one iteration is performed. Default value is `false`.
- `thundra_lambda_warmup_randomizationBypassInterval`: `Long` typed property that configures the time interval in milliseconds to bypass randomization and directly use invocation count. Default value is `900.000 milliseconds` (`15 minutes`).
- `thundra_lambda_warmup_disableRandomization`: `Boolean` typed property that disables randomized invocation count behaviour. Note that invocations counts are randomized for preventing full load (all containers are busy with warmup invocations) on AWS Lambda during warmup to leave some AWS Lambda containers free/available for real requests and simulating real environment as much as possible. Default value is `false`.
- `thundra_lambda_warmup_warmupFunctionAlias`: `String` typed property that configures alias to be used as qualifier while invoking Lambda functions to warmup.
- `thundra_lambda_warmup_throwErrorOnFailure`: `Boolean` typed property that enables throwing error behaviour if the warmup invocation fails for some reason. Default value is `false`.
- `thundra_lambda_warmup_dontWaitBetweenInvocationRounds`: `Boolean` typed property that disables waiting behaviour between each warmup invocation round. Default value is `false`.

### Configurations of StatAwareWarmupStrategy

- `thundra_lambda_warmup_functionInstanceIdleTime`: `Long` typed property that configures the passed time in milliseconds to consider a Lambda function is idle. Default value is `1.800.000 milliseconds` (`30 minutes`).
- `thundra_lambda_warmup_warmupScaleFactor`: `Float` typed property that configures scale factor to increase/decrease Lambda invocation count according to its stat (it is hot or not). Default value is `2.0`.
- `thundra_lambda_warmup_disableWarmupScale`: `Boolean` typed property that disables warmup scale behaviour which is enabled by default and scale factor is configured by `thundra_lambda_warmup_warmupScaleFactor` property as mentioned above.
