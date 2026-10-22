#!/usr/bin/env node
import { App } from 'aws-cdk-lib';
import { REGION } from '../lib/config';
import { LicsysStack } from '../lib/licsys-stack';

const app = new App();
new LicsysStack(app, 'LicsysNext', {
  // Account-agnostic by default so `cdk synth` needs no AWS credentials (no real account for this demo).
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: REGION },
  imageTag: app.node.tryGetContext('imageTag') ?? 'latest',
  description: 'LICSYS Next (fictional demo system): microservices replacing the LICNIGHT COBOL batch',
});
