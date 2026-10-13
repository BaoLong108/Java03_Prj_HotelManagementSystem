import { Card, EmptyState, LinkButton, PageHeader } from '../../shared/ui'

// Replace this state with the agreed room API, not a client-side availability calculation.
export function RoomsPage() {
  return <><PageHeader eyebrow="KHÔNG GIAN NGHỈ NGƠI" title="Khám phá loại phòng" description="Tìm không gian phù hợp với kỳ nghỉ của bạn." />
    <Card><EmptyState icon="bed" title="Các loại phòng đang được cập nhật"
      description="Thông tin phòng và tiện nghi sẽ sớm có mặt tại đây. Vui lòng quay lại sau."
      action={<LinkButton to="/" variant="secondary">Về tổng quan</LinkButton>} /></Card>
  </>
}
