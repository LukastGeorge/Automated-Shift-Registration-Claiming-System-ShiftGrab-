# ShiftGrab - Hệ thống Đăng ký & Giành ca làm việc tự động

## Kiến trúc & Các quyết định kỹ thuật (Trả lời câu hỏi "TẠI SAO?")

| Quyết định kỹ thuật | Vấn đề giải quyết | Tại sao chọn giải pháp này? |
| :--- | :--- | :--- |
| **Strategy Pattern cho Lock (`ShiftLockProvider`)** | Tách biệt cơ chế khóa đồng thời khỏi nghiệp vụ. | Giai đoạn MVP dùng `ReentrantLock` theo từng `shiftId` để chạy gọn nhẹ. Khi scale nhiều instance server chỉ cần viết thêm `RedisLockProvider` mà không sửa code nghiệp vụ. |
| **Database Optimistic Locking (`@Version`)** | Phòng thủ lớp thứ 2 chống ghi đè dữ liệu khi nhiều thread cập nhật cùng lúc. | Đảm bảo toàn vẹn dữ liệu ở tầng Database mà không giữ kết nối lâu như `SELECT FOR UPDATE`. |
| **Unique Constraint `(shift_id, user_id)`** | Nhân viên bấm đúp nút đăng ký 2 lần liên tiếp. | Đảm bảo tính **Idempotency** ở cấp Database, chặn đứng việc 1 người chiếm 2 suất của cùng 1 ca. |
| **Composite Index `(shift_date, status)`** | Lọc nhanh danh sách ca còn trống theo ngày. | Đưa độ phức tạp truy vấn từ $O(N)$ (quét toàn bảng) về $O(\log N)$ trên cây B-Tree. |

## Cách chạy trên VS Code

1. Mở thư mục `backend` và chạy `ShiftGrabApplication.java`.
2. Hệ thống tự động tạo file `shiftgrab.db` và nạp sẵn dữ liệu mẫu (5 nhân viên, 5 ca làm việc).
3. Click trực tiếp vào file `shiftgrab.db` trong VS Code (dùng extension SQLite Viewer / Database Client) để xem bảng dữ liệu.
4. Truy cập Swagger UI để test API: `http://localhost:8080/swagger-ui.html`
