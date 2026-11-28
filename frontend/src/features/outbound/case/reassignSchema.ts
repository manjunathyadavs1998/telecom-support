import { z } from 'zod'

/** Reassign (04-outbound/frontend.md §6): an agent other than the current one; reason trimmed 5–500 (backend R6). */
export function reassignSchema(currentUserId: number | null | undefined) {
  return z.object({
    assigneeUserId: z
      .string()
      .regex(/^\d+$/, 'Choose an agent.')
      .refine((value) => Number(value) !== currentUserId, 'Already assigned to this agent.'),
    reason: z.string().trim().min(5, 'Enter at least 5 characters.').max(500, 'Use at most 500 characters.'),
  })
}

export type ReassignInput = { assigneeUserId: string; reason: string }
