import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ApiError, errorMessage } from '../../shared/api/client'
import { Alert, Button, Field } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { useAuth } from './AuthProvider'
import { loginDestination, safeReturnPath } from './navigation'
import { validateAuth } from './validation'

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const isRegister = mode === 'register'
  const [busy, setBusy] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const request = useRef<AbortController | null>(null)
  const requestedPath = safeReturnPath(location.state?.from)
  const destination = loginDestination(auth.user?.roles ?? [], requestedPath)

  useEffect(() => {
    if (auth.status === 'authenticated') navigate(destination, { replace: true })
  }, [auth.status, destination, navigate])
  useEffect(() => {
    setError(''); setErrors({}); setShowPassword(false); setBusy(false)
    return () => { request.current?.abort(); request.current = null }
  }, [mode])

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (request.current || auth.status === 'loading') return
    const form = event.currentTarget
    const data = new FormData(form)
    const email = String(data.get('email') ?? '').trim()
    const password = String(data.get('password') ?? '')
    const fullName = String(data.get('fullName') ?? '').trim()
    const phoneNumber = String(data.get('phoneNumber') ?? '').trim()
    const validation = validateAuth({ email, password, fullName, phoneNumber, confirmPassword: String(data.get('confirmPassword') ?? '') }, isRegister)
    setErrors(validation); setError('')
    if (Object.keys(validation).length) {
      const field = form.elements.namedItem(Object.keys(validation)[0])
      if (field instanceof HTMLInputElement) field.focus()
      return
    }
    const controller = new AbortController()
    request.current = controller
    setBusy(true)
    try {
      if (isRegister) {
        const signedIn = await auth.register({ email, password, fullName, phoneNumber: phoneNumber || undefined }, controller.signal)
        if (!signedIn) navigate('/login', { replace: true, state: { from: requestedPath, message: 'Đăng ký thành công' } })
      } else await auth.login({ email, password }, controller.signal)
      form.reset()
    } catch (failure) {
      if (controller.signal.aborted) return
      setError(errorMessage(failure))
      if (failure instanceof ApiError) {
        setErrors(failure.details)
        if (isRegister && failure.status === 409 && failure.code === 'EMAIL_ALREADY_EXISTS') {
          setError('Email đã được sử dụng')
          setErrors({ email: 'Email đã được sử dụng' })
        }
      }
    } finally {
      if (request.current === controller) { request.current = null; setBusy(false) }
    }
  }

  return <div className="auth-layout">
    <section className="auth-story"><span className="eyebrow light">HOTEL PMS MINI</span>
      <h1>Mỗi chuyến đi,<br />một khởi đầu mới.</h1>
      <p>Tìm không gian nghỉ ngơi phù hợp và quản lý kỳ nghỉ của bạn ở cùng một nơi.</p>
      <div className="key-art" aria-hidden="true"><div className="key-art-orbit" /><div className="keycard"><Icon name="building" size={40} /><span>HOTEL PMS</span><strong>Welcome<br />to your stay.</strong><span className="keycard-chip" /><small>A LITTLE SPACE. A BETTER STAY.</small></div></div>
      <div className="story-footer"><Icon name="shield" size={18} /><span>Tài khoản của bạn. Trải nghiệm của bạn.</span></div>
    </section>
    <section className="auth-form-panel" aria-labelledby="auth-title"><div className="auth-form-heading">
      <span className="small-icon teal"><Icon name={isRegister ? 'user' : 'key'} /></span>
      <p className="eyebrow">{isRegister ? 'BẮT ĐẦU HÀNH TRÌNH' : 'RẤT VUI ĐƯỢC GẶP LẠI BẠN'}</p>
      <h2 id="auth-title">{isRegister ? 'Tạo tài khoản' : 'Đăng nhập'}</h2>
      <p className="muted">{isRegister ? 'Đăng ký để đặt phòng và theo dõi kỳ nghỉ của bạn.' : 'Tiếp tục với tài khoản Hotel PMS của bạn.'}</p>
    </div>
      {auth.sessionExpired && <Alert tone="warning">{auth.sessionMessage}</Alert>}
      {!isRegister && location.state?.message === 'Đăng ký thành công' && <Alert tone="success">Đăng ký thành công</Alert>}
      {error && <Alert>{error}</Alert>}
      <form onSubmit={submit} key={mode} noValidate aria-busy={busy}>
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
        <Link to={isRegister ? '/login' : '/register'} state={{ from: requestedPath }}>{isRegister ? 'Đăng nhập' : 'Đăng ký ngay'}</Link></p>
      <Link to="/rooms" className="text-link auth-back">Tiếp tục khám phá phòng <Icon name="arrow" size={16} /></Link>
    </section>
  </div>
}
