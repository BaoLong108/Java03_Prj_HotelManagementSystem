import { useState } from 'react'
import { Card, PageHeader } from '../../../shared/ui'
import { CsvDownload, PeriodFields } from '../reports/ReportControls'
import { presetPeriod } from '../reports/reportApi'
import '../reports/reports.css'

export function BookingExportPage() {
  const [period, setPeriod] = useState(() => presetPeriod('7'))
  return <><PageHeader title="Xuất lịch đặt phòng" description="Đối chiếu booking và lịch sử chặn phòng theo giờ Việt Nam." />
    <Card><div className="report-controls"><PeriodFields period={period} onChange={setPeriod} /><CsvDownload period={period} /></div>
      <p className="small muted">Tối đa 92 ngày; không gồm ngày đến. CSV UTF-8 có hai phần BOOKINGS và ROOM_BLOCKS, gồm cả khoảng chặn đã mở. Giá lấy từ booking, liên hệ đã che.</p>
    </Card></>
}
