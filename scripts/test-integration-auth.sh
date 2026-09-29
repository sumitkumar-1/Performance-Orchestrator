#!/usr/bin/env bash
set -eu

# Replace these values before running. curl prompts for each password.
JFROG_BASE_URL='https://artifactory.domain'
BITBUCKET_BASE_URL='https://stash.domain'
AD_USERNAME='YOUR_AD_USERNAME'
BITBUCKET_USER_SLUG='YOUR_USER_SLUG'

# These calls create real tokens and print them. Do not share the token output.
# JFrog: request a token valid for 30 minutes.
echo 'Testing JFrog token creation (enter your AD password when prompted)...'
curl --silent --show-error \
  --user "$AD_USERNAME" \
  --request POST \
  "$JFROG_BASE_URL/access/api/v1/tokens" \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=applied-permissions/user' \
  --data-urlencode 'expires_in=1800' \
  --data-urlencode 'refreshable=false' \
  --data-urlencode 'description=perf-orchestrator-integration-test' \
  --write-out '\nHTTP %{http_code}\n'

# Bitbucket: request a repository-read token valid for one day.
# Delete this test token from your Bitbucket account settings after testing.
echo 'Testing Bitbucket token creation (enter your AD password when prompted)...'
curl --silent --show-error \
  --user "$AD_USERNAME" \
  --request PUT \
  "$BITBUCKET_BASE_URL/rest/access-tokens/latest/users/$BITBUCKET_USER_SLUG" \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --data '{
    "name": "perf-orchestrator-integration-test",
    "permissions": ["REPO_READ"],
    "expiryDays": 1
  }' \
  --write-out '\nHTTP %{http_code}\n'
