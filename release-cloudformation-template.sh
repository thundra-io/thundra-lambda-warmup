AWS_ACCESS_KEY_ID=$(./get-aws-profile.sh --profile=thundra --key)
AWS_SECRET_KEY=$(./get-aws-profile.sh --profile=thundra --secret)

mvn -DAWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -DAWS_SECRET_KEY=$AWS_SECRET_KEY s3-upload:s3-upload -P release-cloudformation-template
