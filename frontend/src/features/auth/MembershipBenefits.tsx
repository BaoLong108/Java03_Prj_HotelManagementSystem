import { Icon } from '../../shared/ui/Icon'

export function MembershipBenefits({ compact = false }: { compact?: boolean }) {
  return <div className={`membership-tiers ${compact ? 'membership-tiers--compact' : ''}`}>
    {[{ name: 'Standard', threshold: 'Dưới 10.000.000 ₫', rate: '0%', description: 'Khởi đầu hành trình của bạn', icon: 'leaf' as const },
      { name: 'VIP', threshold: 'Từ 10.000.000 ₫', rate: '10%', description: 'Thêm ưu đãi cho mỗi kỳ nghỉ', icon: 'star' as const },
      { name: 'Diamond', threshold: 'Từ 30.000.000 ₫', rate: '15%', description: 'Đặc quyền cho sự gắn bó', icon: 'sparkle' as const }].map((tier) =>
      <section className={`tier-card tier-card--${tier.name.toLowerCase()}`} key={tier.name}><Icon name={tier.icon} size={25} /><h3>{tier.name}</h3><p>{tier.description}</p><strong>{tier.rate}<small> giảm tiền phòng</small></strong><div className="tier-threshold">{tier.threshold}<span>chi tiêu hợp lệ tích lũy</span></div></section>)}
  </div>
}
