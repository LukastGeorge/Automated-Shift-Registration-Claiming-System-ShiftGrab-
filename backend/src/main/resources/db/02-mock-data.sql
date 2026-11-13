INSERT OR IGNORE INTO users (id, full_name, email, role, hourly_rate) VALUES
(1, 'Quản Lý Cửa Hàng', 'manager@shiftgrab.vn', 'MANAGER', 45000.00),
(2, 'Nguyễn Văn An', 'an.nv@shiftgrab.vn', 'STAFF', 25000.00),
(3, 'Trần Thị Bình', 'binh.tt@shiftgrab.vn', 'STAFF', 25000.00),
(4, 'Lê Hoàng Cường', 'cuong.lh@shiftgrab.vn', 'STAFF', 27000.00),
(5, 'Phạm Minh Duy', 'duy.pm@shiftgrab.vn', 'STAFF', 25000.00);

INSERT OR IGNORE INTO shifts (id, shift_name, shift_date, start_time, end_time, max_slots, booked_slots, status) VALUES
(1, 'Ca Sáng (Barista)', date('now', '+1 day'), '07:00:00', '12:00:00', 2, 1, 'OPEN'),
(2, 'Ca Chiều (Phục vụ)', date('now', '+1 day'), '12:00:00', '17:00:00', 2, 0, 'OPEN'),
(3, 'Ca Tối (Thu ngân)', date('now', '+1 day'), '17:00:00', '22:00:00', 1, 0, 'OPEN'),
(4, 'Ca Sáng Cuối Tuần', date('now', '+2 days'), '07:00:00', '12:00:00', 3, 2, 'OPEN'),
(5, 'Ca Tối Cuối Tuần (Hot)', date('now', '+2 days'), '17:00:00', '22:30:00', 1, 0, 'OPEN');
