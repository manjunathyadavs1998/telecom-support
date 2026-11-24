import { z } from 'zod'

// Mirrors AuthController.LoginRequest validation messages.
export const loginSchema = z.object({
  username: z.string().trim().min(1, 'Enter your mobile number or employee ID').max(64),
  password: z.string().min(1, 'Enter your password').max(128),
})

export type LoginForm = z.infer<typeof loginSchema>
