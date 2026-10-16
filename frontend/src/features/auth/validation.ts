export type AuthFields = { email: string; password: string; fullName: string; phoneNumber: string; confirmPassword: string }

// Matches RegisterRequest, LoginRequest and PasswordValidator in M1-03.
// No phone regex: backend only limits this optional field to 30 characters.
export function validateAuth(fields: AuthFields, register: boolean): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!fields.email.trim()) errors.email = 'Vui lòng nhập email.'
  else if (fields.email.length > 255 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(fields.email)) errors.email = 'Vui lòng nhập email hợp lệ, tối đa 255 ký tự.'
  if (!fields.password.trim()) errors.password = 'Vui lòng nhập mật khẩu.'
  else if (fields.password.length > 72 || new TextEncoder().encode(fields.password).length > 72
      || (register && fields.password.length < 8)) {
    errors.password = register ? 'Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte UTF-8.' : 'Mật khẩu không được vượt quá 72 byte UTF-8.'
  }
  if (register) {
    if (!fields.fullName.trim()) errors.fullName = 'Vui lòng nhập họ và tên.'
    else if (fields.fullName.length > 150) errors.fullName = 'Họ và tên tối đa 150 ký tự.'
    if (fields.phoneNumber.length > 30) errors.phoneNumber = 'Số điện thoại tối đa 30 ký tự.'
    if (!fields.confirmPassword) errors.confirmPassword = 'Vui lòng nhập lại mật khẩu.'
    else if (fields.password !== fields.confirmPassword) errors.confirmPassword = 'Mật khẩu nhập lại chưa khớp.'
  }
  return errors
}
