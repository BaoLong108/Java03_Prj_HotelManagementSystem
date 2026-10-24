import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { MembershipBenefits } from '../auth/MembershipBenefits'
import { StaySearchForm } from '../rooms/StaySearchForm'
import { LinkButton } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import courtyard from '../../assets/hotel-courtyard.webp'

export function DashboardPage() {
  const { user } = useAuth()
  return <>
    <section className="hotel-hero"><img className="hero-image" src={courtyard} alt="Minh họa sân vườn khách sạn với mái vòm đá và mặt nước trong ánh nắng chiều" fetchPriority="high" width="1672" height="941" />
      <div className="hero-shade" /><div className="hero-copy"><span className="hero-kicker"><i /> YOUR NEXT CHAPTER OF REST</span><h1>Một khoảng nghỉ,<br /><em>thật riêng.</em></h1>
        <p>Gác lại những vội vã. Tìm một không gian để<br className="desktop-break" /> chậm lại, kết nối và tận hưởng từng khoảnh khắc.</p>
        <Link className="hero-link" to="/rooms">Khám phá không gian <span><Icon name="arrow" size={20} /></span></Link></div>
      <div className="hero-caption"><span>THE ART OF A SIMPLE STAY</span><small>Hình ảnh minh họa ý tưởng</small></div>
    </section>
    <div className="home-search-wrap"><StaySearchForm /><p className="search-footnote"><Icon name="info" size={14} />Danh mục phòng đang được cập nhật. Bạn có thể chọn trước lịch nghỉ.</p></div>
    <section className="home-section intro-section" id="experience"><div><p className="eyebrow">CHÀO MỪNG ĐẾN HOTEL PMS</p><h2>Không chỉ là nơi ở.<br /><span>Là thời gian cho chính bạn.</span></h2></div><p>Những kỳ nghỉ đáng nhớ bắt đầu từ lựa chọn phù hợp. Khám phá không gian, lên kế hoạch và quản lý hành trình của bạn trong một trải nghiệm liền mạch.</p></section>
    <section className="home-section room-discovery"><div className="section-heading"><div><p className="eyebrow">FIND YOUR SPACE</p><h2>Phòng & không gian</h2></div><Link className="text-link" to="/rooms">Xem loại phòng <Icon name="arrow" size={17} /></Link></div>
      <div className="room-preview"><div className="room-preview-visual"><img src={courtyard} alt="Không gian kiến trúc khách sạn minh họa" loading="lazy" width="1672" height="941" /><span className="image-caption">A SLOWER KIND OF STAY · MINH HỌA</span></div>
        <div className="room-preview-copy"><span className="outline-label">BỘ SƯU TẬP KHÔNG GIAN</span><h3>Kỳ nghỉ của bạn,<br />theo cách của bạn.</h3><p>Danh mục loại phòng và tiện nghi đang được chuẩn bị. Giá và tình trạng phòng sẽ được xác nhận cho đúng ngày bạn chọn.</p><LinkButton to="/rooms" variant="secondary">Chọn lịch nghỉ <Icon name="arrow" size={17} /></LinkButton><small>Chưa mở đặt phòng trực tuyến.</small></div></div>
    </section>
    <section className="stay-values"><div className="home-section"><div className="section-heading"><div><p className="eyebrow">THOUGHTFULLY SIMPLE</p><h2>Mọi điều cần thiết, rõ ràng hơn.</h2></div></div><div className="value-grid">
      <div><Icon name="bed" size={29} /><h3>Chọn không gian phù hợp</h3><p>Khám phá theo loại phòng. Thông tin sức chứa và tiện nghi được trình bày cùng nhau.</p></div>
      <div><Icon name="shield" size={29} /><h3>Hiểu rõ kỳ nghỉ của bạn</h3><p>Xem giá và ưu đãi được xác nhận trước khi quyết định giữ chỗ, thanh toán.</p></div>
      <div><Icon name="sparkle" size={29} /><h3>Gắn bó thêm đặc quyền</h3><p>Tích lũy từ những kỳ nghỉ hoàn tất, nhận ưu đãi theo hạng thành viên.</p></div>
    </div></div></section>
    <section className="home-section membership-section" id="membership"><div className="section-heading"><div><p className="eyebrow">A LITTLE MORE, JUST FOR YOU</p><h2>Mỗi lần trở lại, thêm một đặc quyền.</h2></div><Link className="text-link" to={user ? '/account' : '/register'}>{user ? 'Xem tài khoản' : 'Tham gia thành viên'}<Icon name="arrow" size={17} /></Link></div>
      <MembershipBenefits /><p className="membership-note"><Icon name="info" size={15} />Mỗi 10.000 ₫ chi tiêu hợp lệ được 1 điểm, ghi nhận khi kỳ nghỉ đã thanh toán và hoàn tất; không tính khoản hoàn tiền.</p>
    </section>
    <section className="home-section closing-note"><span className="eyebrow">YOUR STAY STARTS HERE</span><h2>Một kỳ nghỉ tốt đẹp.<br />Bắt đầu từ một lựa chọn nhỏ.</h2><LinkButton to="/rooms">Lên kế hoạch kỳ nghỉ <Icon name="arrow" size={18} /></LinkButton></section>
  </>
}
