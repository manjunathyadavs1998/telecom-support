import { z } from 'zod'

/** Login form (frontend.md §6): the username is trimmed, the password never is; bounds match `LoginRequest`. */
export const loginSchema = z.object({
  username: z
    .string()
    .trim()
    .min(1, 'Enter your mobile number or employee ID')
    .max(64, 'Enter your mobile number or employee ID (at most 64 characters)'),
  password: z.string().min(1, 'Enter your password').max(128, 'Enter your password (at most 128 characters)'),
})

export type LoginValues = z.infer<typeof loginSchema>

export const LOGIN_FIELDS = ['username', 'password'] as const satisfies readonly (keyof LoginValues)[]
