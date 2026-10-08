# 🛠️ Custom Folia Server - Patch Notes & Modifications

Bản fork tùy chỉnh của **Folia** (dựa trên Paperweight v2 / Minecraft 1.20.x+) được tối ưu hóa cho hệ thống quản lý console gọn gàng, tăng cường bảo mật hệ thống và bảo vệ plugin cốt lõi.

---

## 📌 Danh Sách Các Thay Đổi (Key Features)

### 1. 🧹 Tối Ưu Hóa & Lọc Log Console (`ConsoleLogFilter.java`)
- **Tự động rút gọn log ngắt kết nối:** Chuyển đổi các thông báo Kick/Ban dài dòng thành 1 dòng duy nhất gọn nhẹ:  
  `Disconnecting <player> (<ip>): từ chối kết nối.`
- **Loại bỏ log rác (Multi-line ban details):** Lọc bỏ hoàn toàn các dòng tin nhắn chi tiết bị rơi xuống hàng khi người chơi bị ban hoặc kick (`lost connection:`, `Người ban:`, `Ngày ban:`, `Thời gian mở khoá:`, `Lý do:`, `Discord:`,...).
- **Nổi bật cảnh báo JVM (ANSI Highlighting):** Tự động chuyển toàn bộ các dòng log cảnh báo hệ thống dạng `WARNING:` (như cảnh báo `sun.misc.Unsafe` của Java/JVM) sang màu **vàng sáng (ANSI Yellow)** giúp Dễ nhận biết trên Terminal/Console.
- **Chống lặp log vô tận:** Tích hợp cơ chế `ThreadLocal` ngăn chặn Filter bị lặp vô tận khi tự khởi tạo log mới.

---

### 2. 📊 Log Thông Tin Bản Build Khi Khởi Động (`DedicatedServer.java`)
Thêm khối log hiển thị trực quan ngay sau khi máy chủ khởi động hoàn tất (`Done (...)! For help, type "help"`):
- **Folia Server Version:** Phiên bản đầy đủ của server.
- **Build Date & Time:** Ngày và giờ thực hiện biên dịch bản build (định dạng `dd/MM/yyyy HH:mm:ss`).
- **Git Commit ID:** Mã commit hash ngắn (Short Commit ID) của kho chứa.

---

### 3. 🛡️ Vô Hiệu Hóa Lệnh Mặc Định & Lệnh Nguy Hiểm (`Commands.java`)
Đã comment lại việc đăng ký các lệnh Vanilla gốc trong lõi Minecraft để tránh bị khai thác bảo mật và nhường quyền cho plugin chuyên dụng:
- **Nhóm lệnh Ban/Pardon mặc định:** Khóa `/ban`, `/ban-ip`, `/banlist`, `/pardon`, `/pardon-ip` (để sử dụng hoàn toàn qua plugin quản lý riêng như BetterBan, LiteBans).
- **Nhóm lệnh Admin & Hệ thống nguy hiểm:**
    - `/stop`: Chống lệnh tắt server đột ngột từ trong game.
    - `/publish`: Chống mở cổng LAN nội bộ.
    - `/jfr` & `/debug`: Chống kích hoạt ghi dump đĩa và debug gây tràn ổ đĩa hoặc lag CPU.

---

### 4. 🔒 Bảo Vệ AntiCheat Ở Cấp Độ Core (`SimplePluginManager.java`)
- **Khóa hàm `disablePlugin()`:** Can thiệp trực tiếp vào `SimplePluginManager` của Bukkit/Folia.
- **Chống PlugMan / PlugManX:** Ngăn chặn tuyệt đối mọi hành vi sử dụng plugin quản lý (PlugMan, FastAsyncWorldEdit,...) hoặc lệnh unload/reload để tắt plugin AntiCheat tự viết.

---

## 🛠️ Hướng Dẫn Biên Dịch & Cập Nhật Patches

```powershell
# 1. Biên dịch kiểm tra mã nguồn Java
.\gradlew :folia-server:compileJava

# 2. Tạo file paperclip jar chạy máy chủ
.\gradlew createPaperclipJar

# 3. Rebuild toàn bộ patches ở thư mục gốc
.\gradlew rebuildPaperServerPatches

# 4. Git Commit & Push lên GitHub
git add .
git commit -m "feat: custom console filtering, startup build info, command disabling, and core anticheat protection"
git push -u origin HEAD