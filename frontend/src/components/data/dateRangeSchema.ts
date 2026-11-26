import { z } from 'zod'
import { MAX_RANGE_DAYS, todayIst, validateRange } from '@/lib/dateWindow'

/** Date range form (frontend.md §6): both dates required; `from ≤ to`; `to ≤ todayIst()`; at most `maxDays` days. */
export function dateRangeSchema({ today = todayIst(), maxDays = MAX_RANGE_DAYS }: { today?: string; maxDays?: number } = {}) {
  return z
    .object({ from: z.string(), to: z.string() })
    .superRefine((range, ctx) => {
      const problem = validateRange(range, { today, maxDays })
      if (problem) ctx.addIssue({ code: 'custom', path: [problem.field], message: problem.message })
    })
}
