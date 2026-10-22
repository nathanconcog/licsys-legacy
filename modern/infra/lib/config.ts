/**
 * Single source of truth for the LICSYS Next deployment, taken from
 * modern/ARCHITECTURE.md. The stack and the architecture diagram are both
 * generated from this file.
 */

export const REGION = 'ap-southeast-2';
export const BUS_NAME = 'licsys-bus';

export interface TableDef {
  readonly name: string;
  readonly partitionKey: string;
}

export interface QueueDef {
  readonly name: string;
  /** EventBridge event pattern, exactly as in localstack/init-aws.sh. */
  readonly pattern: { detailType?: string[]; sourcePrefix?: string };
  readonly description: string;
}

export interface ServiceDef {
  readonly id: string;
  readonly name: string;
  readonly context: string;
  readonly port: number;
  readonly eventSource: string;
  readonly table: TableDef;
  readonly consumes: string[];
  /** ALB path patterns, highest priority first. */
  readonly paths: { patterns: string[]; priority: number }[];
  readonly rules: string[];
}

export const QUEUES: QueueDef[] = [
  { name: 'licence-inbox', pattern: { detailType: ['DemeritPointsChanged'] }, description: 'licence-service projection of demerit points' },
  { name: 'demerit-inbox', pattern: { detailType: ['DriverRegistered', 'LicenceProgressed'] }, description: 'demerit-service driver and class projection' },
  { name: 'notification-inbox', pattern: { detailType: ['SuspensionThresholdReached', 'LicenceProgressed'] }, description: 'notices to issue' },
  { name: 'activity-inbox', pattern: { sourcePrefix: 'licsys.' }, description: 'every domain event, for the activity feed' },
];

export const SERVICES: ServiceDef[] = [
  {
    id: 'Licence',
    name: 'licence-service',
    context: 'Driver Licensing',
    port: 8081,
    eventSource: 'licsys.licence',
    table: { name: 'licence-drivers', partitionKey: 'driverId' },
    consumes: ['licence-inbox'],
    paths: [{ patterns: ['/api/drivers', '/api/drivers/*', '/api/progression/*'], priority: 20 }],
    rules: ['BR-01', 'BR-02', 'BR-03'],
  },
  {
    id: 'Demerit',
    name: 'demerit-service',
    context: 'Demerits & Offences',
    port: 8082,
    eventSource: 'licsys.demerit',
    table: { name: 'demerit-ledger', partitionKey: 'driverId' },
    consumes: ['demerit-inbox'],
    // Must win over licence-service's /api/drivers/* rule.
    paths: [{ patterns: ['/api/drivers/*/demerits', '/api/offences', '/api/offences/*'], priority: 10 }],
    rules: ['BR-04', 'BR-05'],
  },
  {
    id: 'Registration',
    name: 'registration-service',
    context: 'Vehicle Registration',
    port: 8083,
    eventSource: 'licsys.registration',
    table: { name: 'registration-renewals', partitionKey: 'renewalId' },
    consumes: [],
    paths: [{ patterns: ['/api/renewals', '/api/renewals/*'], priority: 30 }],
    rules: ['BR-06'],
  },
  {
    id: 'Notification',
    name: 'notification-service',
    context: 'Notifications & Activity',
    port: 8084,
    eventSource: 'licsys.notification',
    table: { name: 'notification-activity', partitionKey: 'eventId' },
    consumes: ['notification-inbox', 'activity-inbox'],
    paths: [{ patterns: ['/api/notices', '/api/notices/*', '/api/activity', '/api/activity/*'], priority: 40 }],
    rules: [],
  },
];

/** Nightly progression sweep: replaces STEP020 (LICUPG) of jcl/LICNIGHT.jcl. */
export const PROGRESSION_SCHEDULE = {
  // LICNIGHT ran after the offence feed landed (approx 01:30 local).
  expression: 'cron(0 2 * * ? *)',
  timezone: 'Australia/Sydney',
  path: '/api/progression/run',
};

export interface LegacyStep {
  readonly step: string;
  readonly program: string;
  readonly legacy: string;
  readonly modern: string;
}

export const JCL_MAPPING: LegacyStep[] = [
  {
    step: 'STEP010',
    program: 'DMRPTS',
    legacy: 'Post daily offence file to DRVMAST, double demerits, write NOTICES',
    modern: 'demerit-service POST /api/offences (real time) -> EventBridge DemeritPointsChanged / SuspensionThresholdReached -> notification-service',
  },
  {
    step: 'STEP020',
    program: 'LICUPG',
    legacy: 'Progress P1->P2 and P2->OP, write DRVMAST.NEW (COND=(0,NE,STEP010))',
    modern: 'EventBridge Scheduler (nightly) -> progression-trigger Lambda -> licence-service POST /api/progression/run',
  },
  {
    step: 'STEP030',
    program: 'REGFEE',
    legacy: 'Price registration renewals file, write REGO.FEES',
    modern: 'registration-service POST /api/renewals/quote and /api/renewals (on demand, no batch)',
  },
];
