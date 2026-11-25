import type { Me, Permission } from '@/types/api'

// One `Me` per role combination of backend.md §3.1, with exactly the §3 permission set (frontend.md §10 Fixtures).
const CUSTOMER: Permission[] = [
  'customer.profile.read',
  'customer.profile.update',
  'plan.read',
  'plan.change',
  'promotion.read',
  'promotion.claim',
  'case.create',
  'case.read',
  'payment.read',
  'payment.initiate',
]
const INBOUND_AGENT: Permission[] = [
  'customer.profile.read',
  'customer.lookup',
  'customer.verify',
  'plan.read',
  'promotion.read',
  'case.create.onbehalf',
  'case.category.internal',
  'case.read',
  'case.note.internal',
  'payment.read',
]
const REACTIVATION_APPROVER: Permission[] = ['service.reactivate']
const OUTBOUND_AGENT: Permission[] = [
  'case.read',
  'case.read.queue',
  'case.assign',
  'case.status.update',
  'case.note.internal',
  'case.bookmark',
  'analytics.read',
]
const OUTBOUND_SUPERVISOR: Permission[] = ['case.assign.any']
const BUSINESS_ANALYST: Permission[] = ['analytics.read', 'audit.read', 'reports.export']
const BUSINESS_AUDITOR: Permission[] = ['reports.export.pii']

const TEAM = { INBOUND: 1, OUTBOUND_N: 2, OUTBOUND_S: 3, BUSINESS: 4 } as const

const union = (...sets: Permission[][]): Permission[] => [...new Set(sets.flat())]

export const meCustomer: Me = {
  id: 1,
  username: '+919800000001',
  displayName: 'Customer One',
  userType: 'CUSTOMER',
  teamId: null,
  customerId: null,
  roles: ['CUSTOMER'],
  permissions: CUSTOMER,
}

export const meInbound: Me = {
  id: 5,
  username: 'IN001',
  displayName: 'Inbound Agent',
  userType: 'STAFF',
  teamId: TEAM.INBOUND,
  customerId: null,
  roles: ['INBOUND_AGENT'],
  permissions: INBOUND_AGENT,
}

export const meInboundApprover: Me = {
  id: 6,
  username: 'IN002',
  displayName: 'Inbound Approver',
  userType: 'STAFF',
  teamId: TEAM.INBOUND,
  customerId: null,
  roles: ['INBOUND_AGENT', 'REACTIVATION_APPROVER'],
  permissions: union(INBOUND_AGENT, REACTIVATION_APPROVER),
}

export const meOutbound: Me = {
  id: 7,
  username: 'OUT001',
  displayName: 'Outbound Agent',
  userType: 'STAFF',
  teamId: TEAM.OUTBOUND_N,
  customerId: null,
  roles: ['OUTBOUND_AGENT'],
  permissions: OUTBOUND_AGENT,
}

export const meSupervisor: Me = {
  id: 10,
  username: 'SUP001',
  displayName: 'Outbound Supervisor',
  userType: 'STAFF',
  teamId: TEAM.OUTBOUND_N,
  customerId: null,
  roles: ['OUTBOUND_AGENT', 'OUTBOUND_SUPERVISOR'],
  permissions: union(OUTBOUND_AGENT, OUTBOUND_SUPERVISOR),
}

export const meAnalyst: Me = {
  id: 11,
  username: 'BUS001',
  displayName: 'Business Analyst',
  userType: 'STAFF',
  teamId: TEAM.BUSINESS,
  customerId: null,
  roles: ['BUSINESS_ANALYST'],
  permissions: BUSINESS_ANALYST,
}

export const meAuditor: Me = {
  id: 12,
  username: 'AUD001',
  displayName: 'Business Auditor',
  userType: 'STAFF',
  teamId: TEAM.BUSINESS,
  customerId: null,
  roles: ['BUSINESS_ANALYST', 'BUSINESS_AUDITOR'],
  permissions: union(BUSINESS_ANALYST, BUSINESS_AUDITOR),
}

/** All 7 role combinations, keyed by seed username. */
export const meFixtures = {
  customer: meCustomer,
  IN001: meInbound,
  IN002: meInboundApprover,
  OUT001: meOutbound,
  SUP001: meSupervisor,
  BUS001: meAnalyst,
  AUD001: meAuditor,
} as const satisfies Record<string, Me>
