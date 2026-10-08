import { useEffect, useState } from 'react'
import { Link, NavLink, Route, Routes } from 'react-router-dom'
import { apiRequest } from './shared/api/client'

type Health = { status: string }

function Home() {
  const [apiStatus, setApiStatus] = useState('Đang kiểm tra API…')

  useEffect(() => {
    apiRequest<Health>('/actuator/health')
      .then((health) => setApiStatus(`API: ${health.status}`))
      .catch(() => setApiStatus('API chưa kết nối'))
  }, [])

  return (
    <section>
      <h1>Hotel PMS Mini</h1>
      <p>Bộ khung dự án để nhóm phát triển theo kế hoạch 4 sprint.</p>
      <p className="status">{apiStatus}</p>
      <p>Các màn hình dưới đây là vị trí dành cho các module; nghiệp vụ sẽ được thêm qua PR.</p>
    </section>
  )
}

function Placeholder({ title, owner, tasks }: { title: string; owner: string; tasks: string }) {
  return (
    <section>
      <h1>{title}</h1>
      <p>Phụ trách: {owner}</p>
      <p>Task: {tasks}</p>
      <p>Màn hình đang chờ phần triển khai nghiệp vụ.</p>
    </section>
  )
}

export default function App() {
  return (
    <>
      <header>
        <Link className="brand" to="/">Hotel PMS Mini</Link>
        <nav aria-label="Điều hướng chính">
          <NavLink to="/rooms">Phòng</NavLink>
          <NavLink to="/booking">Đặt phòng</NavLink>
          <NavLink to="/login">Đăng nhập</NavLink>
          <NavLink to="/staff">Staff</NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/rooms" element={<Placeholder title="Danh mục phòng" owner="Lê Phước Sang" tasks="M2-07" />} />
          <Route path="/booking" element={<Placeholder title="Tìm và đặt phòng" owner="Phan Thanh Binh" tasks="M3-05, M3-06" />} />
          <Route path="/login" element={<Placeholder title="Đăng nhập" owner="Hồ Văn Thông Đạt" tasks="M5-02" />} />
          <Route path="/staff" element={<Placeholder title="Khu vực staff" owner="Hồ Văn Thông Đạt" tasks="M5-03, M5-04, M5-05" />} />
          <Route path="*" element={<Placeholder title="Không tìm thấy trang" owner="Nhóm" tasks="—" />} />
        </Routes>
      </main>
    </>
  )
}
