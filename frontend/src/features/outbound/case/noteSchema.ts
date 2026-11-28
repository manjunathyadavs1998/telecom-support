import { z } from 'zod'

export const NOTE_MAX = 2000

/** Note (04-outbound/frontend.md §6): visibility ∈ the allowed options; text trimmed 1–2000. */
export function noteSchema(allowed: readonly ('INTERNAL' | 'PUBLIC')[]) {
  return z.object({
    visibility: z.enum(['INTERNAL', 'PUBLIC']).refine((v) => allowed.includes(v), 'Choose the note type.'),
    text: z.string().trim().min(1, 'Enter the note.').max(NOTE_MAX, `Use at most ${NOTE_MAX} characters.`),
  })
}

export type NoteInput = { visibility: 'INTERNAL' | 'PUBLIC'; text: string }
