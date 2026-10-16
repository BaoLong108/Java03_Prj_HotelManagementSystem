import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ApiError, errorMessage } from '../../shared/api/client'
import { Alert, Button, Field } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { useAuth } from './AuthProvider'
import courtyard from '../../assets/hotel-courtyard.webp'

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const isRegister = mode === 'register'
  const [busy, setBusy] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const requestedPath = location.state?.from
  const destination = typeof requestedPath === 'string' && requestedPath.startsWith('/')
    && !requestedPath.startsWith('//') && !/^\/(login|register)(\/|\?|$)/.test(requestedPath) ? requestedPath : '/account'

  useEffect(() => {
    if (auth.status === 'authenticated') navigate(destination, { replace: true })
  }, [auth.status, destination, navigate])
  useEffect(() => { setError(''); setErrors({}); setShowPassword(false) }, [mode])

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const form = event.currentTarget
    const data = new FormData(form)
    const email = String(data.get('email') ?? '').trim()
    const password = String(data.get('password') ?? '')
    const validation: Record<string, string> = {}
    if (isRegister) {
      if (!String(data.get('fullName') ?? '').trim()) validation.fullName = 'Vui lòng nhập họ và tên.'
      if (!password.trim() || password.length < 8 || new TextEncoder().encode(password).length > 72)
        validation.password = 'Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte UTF-8.'
      if (password !== data.get('confirmPassword')) validation.confirmPassword = 'Mật khẩu nhập lại chưa khớp.'
    }
    setErrors(validation); setError('')
    if (Object.keys(validation).length) return
    setBusy(true)
    try {
      if (isRegister) await auth.register({ email, password, fullName: String(data.get('fullName')).trim(),
        phoneNumber: String(data.get('phoneNumber') ?? '').trim() || undefined })
      else await auth.login({ email, password })
      form.reset()
    } catch (failure) {
      setError(errorMessage(failure))
      if (failure instanceof ApiError) setErrors(failure.details)
    } finally { setBusy(false) }
  }

  return <div className="auth-layout">
    <section className="auth-story"><img src={courtyard} alt="Không gian sân vườn khách sạn minh họa" className="auth-story-image" /><span className="eyebrow light">A LITTLE SPACE. A BETTER STAY.</span>
      <h2 className="auth-story-title">Mỗi chuyến đi,<br />một khởi đầu mới.</h2>
      <p>Tìm không gian nghỉ ngơi phù hợp và quản lý kỳ nghỉ của bạn ở cùng một nơi.</p>
      <div className="story-footer"><Icon name="sparkle" size={22} /><span>Những kỳ nghỉ đáng nhớ.<br />Những đặc quyền dành riêng.</span></div>
    </section>
    <section className="auth-form-panel" aria-labelledby="auth-title"><div className="auth-form-heading">
      <span className="small-icon teal"><Icon name={isRegister ? 'user' : 'key'} /></span>
      <p className="eyebrow">{isRegister ? 'BẮT ĐẦU HÀNH TRÌNH' : 'RẤT VUI ĐƯỢC GẶP LẠI BẠN'}</p>
      <h1 id="auth-title">{isRegister ? 'Tạo tài khoản' : 'Đăng nhập'}</h1>
      <p className="muted">{isRegister ? 'Đăng ký để đặt phòng và theo dõi kỳ nghỉ của bạn.' : 'Tiếp tục với tài khoản Hotel PMS của bạn.'}</p>
    </div>
      {auth.sessionExpired && <Alert tone="warning">Phiên đăng nhập đã hết hạn hoặc không còn hợp lệ. Vui lòng đăng nhập lại.</Alert>}
      {error && <Alert>{error}</Alert>}
      <form onSubmit={submit} key={mode}>
        <fieldset disabled={busy || auth.status === 'loading'} className="form-fields">
          {isRegister && <Field label="Họ và tên" name="fullName" autoComplete="name" placeholder="Nguyễn Ngọc Bảo Long" required maxLength={150} error={errors.fullName} />}
          <Field label="Email" name="email" type="email" autoComplete="username" placeholder="ban@example.com" required maxLength={255} error={errors.email} />
          {isRegister && <Field label="Số điện thoại" name="phoneNumber" type="tel" autoComplete="tel" placeholder="090 123 4567" maxLength={30} error={errors.phoneNumber} />}
          <Field label="Mật khẩu" name="password" type={showPassword ? 'text' : 'password'}
            autoComplete={isRegister ? 'new-password' : 'current-password'} placeholder="Nhập mật khẩu của bạn" required
            minLength={isRegister ? 8 : undefined} maxLength={72} error={errors.password}
            hint={isRegister ? 'Từ 8 ký tự, tối đa 72 byte UTF-8.' : undefined}
            endAdornment={<button className="input-action" type="button" onClick={() => setShowPassword(!showPassword)}
              aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'} aria-pressed={showPassword}><Icon name="eye" size={18} /></button>} />
          {isRegister && <Field label="Nhập lại mật khẩu" name="confirmPassword" type={showPassword ? 'text' : 'password'}
            autoComplete="new-password" placeholder="Nhập lại mật khẩu" required maxLength={72} error={errors.confirmPassword} />}
          <Button type="submit" busy={busy} className="button--full">{busy ? 'Đang xử lý…' : isRegister ? 'Tạo tài khoản' : 'Đăng nhập'}{!busy && <Icon name="arrow" size={18} />}</Button>
        </fieldset>
      </form>
      <p className="auth-switch">{isRegister ? 'Đã có tài khoản? ' : 'Chưa có tài khoản? '}
        <Link to={isRegister ? '/login' : '/register'} state={{ from: destination }}>{isRegister ? 'Đăng nhập' : 'Đăng ký ngay'}</Link></p>
      <Link to="/rooms" className="text-link auth-back">Tiếp tục khám phá phòng <Icon name="arrow" size={16} /></Link>
    </section>
  </div>
}
