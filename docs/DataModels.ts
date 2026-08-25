/**
 * AegisMed — cross-platform data-model reference (TypeScript mirror).
 * The authoritative runtime source of truth is the Kotlin implementation in
 * app/src/main/java/com/aegismed/app/data/db/Entities.kt. Keep both in sync.
 *
 * All timestamps are epoch milliseconds UTC unless noted. `dayEpoch` fields are
 * days-since-epoch (UTC) for parity/cycle math.
 */

export enum Tier {
  CRITICAL = 0, // anticoagulants, insulin, anti-epileptics, immunosuppressants
  STANDARD = 1, // daily maintenance therapy
  ELECTIVE = 2, // OTC supplements / vitamins
}

export enum VerificationMode {
  TAP = 0,
  NFC = 1,
  BARCODE = 2,
}

export enum RuleType {
  FIXED_TIMES = 0, // timesOfDay ["08:00","20:00"]
  INTERVAL_HOURS = 1, // every intervalHours since last dose/start
  ALTERNATING_DAYS = 2, // dayParity 0|1 on epoch-day % 2
  CYCLE = 3, // onDays on, offDays off, from rule start
  TAPER = 4, // taperSteps [{weekOffset, dose}]
  RELATIVE_ANCHOR = 5, // offsetMinutes after anchorKind event
}

export enum AnchorKind {
  WAKE = 0,
  BREAKFAST = 1,
  LUNCH = 2,
  DINNER = 3,
  BEDTIME = 4,
}

export enum RoutineProfile {
  ANY = 0, // active under both routines
  DAY_SHIFT = 1,
  NIGHT_SHIFT = 2,
}

export enum DoseStatus {
  UPCOMING = 0,
  DUE = 1,
  LATE = 2,
  TAKEN = 3,
  SKIPPED = 4,
  MISSED = 5,
}

export interface TaperStep {
  weekOffset: number; // weeks since schedule start
  dose: number; // units per dose during this step
}

export interface Medication {
  id: string;
  name: string;
  strengthValue?: number;
  strengthUnit?: 'mg' | 'mcg' | 'g' | 'ml' | 'iu';
  form?: 'tablet' | 'capsule' | 'liquid' | 'injection' | 'patch' | 'drops';
  tier: Tier;
  verificationMode: VerificationMode;
  barcode?: string;
  nfcTagIdHex?: string;
  sigNotes?: string; // "Take 1 tablet every 12 hours with food"
  active: boolean;
  createdAt: number;
}

export interface ScheduleRule {
  id: string;
  medicationId: string;
  ruleType: RuleType;
  active: boolean;
  timesOfDay?: string[]; // HH:mm, FIXED_TIMES
  intervalHours?: number;
  minIntervalHours: number; // anti-snooze floor; next >= takenAt + floor
  anchorKind?: AnchorKind; // RELATIVE_ANCHOR
  offsetMinutes: number; // e.g. +30 => "30 min after breakfast"
  dayParity?: 0 | 1; // ALTERNATING_DAYS
  onDays?: number; // CYCLE
  offDays?: number;
  taperSteps?: TaperStep[];
  routineProfile: RoutineProfile;
}

export interface DoseLog {
  id: string;
  medicationId: string;
  scheduledFor: number;
  takenAt?: number;
  status: DoseStatus;
  verifiedVia: VerificationMode;
  amount: number;
}

export interface Inventory {
  medicationId: string;
  unitsOnHand: number;
  unitsPerDose: number;
  refillThreshold: number; // alert when projected below this
  lastRefillAt?: number;
}

export type InteractionSeverity = 'contraindicated' | 'major' | 'moderate' | 'minor';

export interface InteractionRuleDef {
  id: string;
  title: string;
  severity: InteractionSeverity;
  description: string;
  mechanism?: string; // CYP3A4 inhibition, vitamin-K antagonism…
  aTokens: string[]; // matched against med names, lowercase substrings
  bTokens: string[];
  bCategory?: 'food' | 'herb' | 'otc' | 'alcohol';
}

export interface InteractionResult {
  ruleId: string;
  severity: InteractionSeverity;
  title: string;
  description: string;
  mechanism?: string;
  involves: { medicationId: string; name: string }[];
  counterpartLabel: string;
}
