-- PostgreSQL RBAC initialization and forward-only upgrades. Keep historical steps for existing DBs.
-- Shared versioned menu/RBAC baseline. Existing IDs and custom grants are preserved.
CREATE TABLE IF NOT EXISTS app_seed_versions (version varchar(100) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now());
DO $seed$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004, 2);
    IF EXISTS (SELECT 1 FROM app_seed_versions WHERE version = 'role-menus-v2') THEN RETURN; END IF;
    INSERT INTO roles (id, role_code, role_name, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, v.code, v.name, now(), now() FROM (VALUES
    ('ADMIN', 'Quản trị viên hệ thống'),
    ('LECTURER', 'Giảng viên'),
    ('FACULTY_STAFF', 'Giảng viên phụ trách công tác Khoa'),
    ('COMMITTEE', 'Giảng viên tham gia Hội đồng'),
    ('STUDENT', 'Sinh viên')
    ) v(code, name)
    ON CONFLICT (role_code) DO UPDATE SET role_name = EXCLUDED.role_name, last_modified_date = now();
    INSERT INTO permissions (id, code, name, module, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, v.code, v.name, v.module, now(), now() FROM (VALUES
    ('DASHBOARD_VIEW', 'Xem tổng quan', 'COMMON'),
    ('NOTIFICATION_VIEW', 'Xem thông báo', 'COMMON'),
    ('PROFILE_EDIT', 'Cập nhật thông tin cá nhân', 'COMMON'),
    ('THESIS_ROUND_VIEW', 'Xem đợt đồ án', 'ROUND'),
    ('THESIS_ROUND_MANAGE', 'Quản lý năm học, học kỳ và đợt', 'ROUND'),
    ('ROUND_PARTICIPANTS_VIEW', 'Xem sinh viên tham gia đợt', 'ROUND'),
    ('LECTURER_DIRECTORY_VIEW', 'Xem hồ sơ và thông tin liên hệ giảng viên', 'DIRECTORY'),
    ('TOPIC_DIRECTORY_VIEW', 'Xem đề tài công khai của giảng viên', 'DIRECTORY'),
    ('STUDENT_PROPOSAL', 'Đăng ký đề tài và chọn GVHD sau khi trao đổi', 'STUDENT'),
    ('STUDENT_PROGRESS_VIEW', 'Xem tiến trình và thực hiện bước đang mở', 'STUDENT'),
    ('STUDENT_DOCUMENT_VIEW', 'Xem hồ sơ đã nộp', 'STUDENT'),
    ('STUDENT_DEFENSE_VIEW', 'Xem lịch bảo vệ của mình', 'STUDENT'),
    ('STUDENT_RESULT_VIEW', 'Xem kết quả bảo vệ của mình', 'STUDENT'),
    ('TOPIC_CREATE', 'Quản lý và công khai đề tài gợi ý của mình', 'LECTURER'),
    ('ADVISOR_REGISTRATION_CONFIRM', 'Xác nhận đăng ký đề tài và nhận hướng dẫn', 'LECTURER'),
    ('ADVISOR_STUDENTS_VIEW', 'Xem sinh viên được phân công hướng dẫn', 'LECTURER'),
    ('ADVISOR_DOCUMENT_APPROVE', 'Duyệt và xác nhận hồ sơ của sinh viên hướng dẫn', 'LECTURER'),
    ('ADVISOR_REVIEW_HISTORY', 'Xem lịch sử duyệt hồ sơ hướng dẫn', 'LECTURER'),
    ('TOPIC_VIEW', 'Xem danh sách đề tài trong phạm vi Khoa', 'FACULTY'),
    ('ADVISOR_ASSIGN', 'Phân công giảng viên hướng dẫn', 'FACULTY'),
    ('THESIS_PROGRESS_VIEW', 'Xem hồ sơ và tiến độ của Khoa', 'FACULTY'),
    ('OUTLINE_APPROVE', 'Duyệt đề cương cấp Khoa', 'FACULTY'),
    ('TOPIC_RENAME_APPROVE', 'Duyệt đổi tên đề tài', 'FACULTY'),
    ('SIMILARITY_CHECK', 'Ghi nhận và duyệt kết quả kiểm tra trùng lắp', 'FACULTY'),
    ('OVERDUE_TRACK', 'Theo dõi hồ sơ quá hạn', 'FACULTY'),
    ('EXCEPTION_HANDLE', 'Xử lý ngoại lệ và gia hạn', 'FACULTY'),
    ('COMMITTEE_MANAGE', 'Quản lý và phân công hội đồng', 'FACULTY'),
    ('DEFENSE_SCHEDULE_MANAGE', 'Sắp xếp lịch bảo vệ', 'FACULTY'),
    ('DEFENSE_RESULTS_VIEW', 'Tổng hợp kết quả bảo vệ', 'FACULTY'),
    ('REPORT_VIEW', 'Xem báo cáo và thống kê theo đợt', 'FACULTY'),
    ('COMMITTEE_MEMBERSHIP_VIEW', 'Xem hội đồng được phân công', 'COMMITTEE'),
    ('COMMITTEE_SCHEDULE_VIEW', 'Xem lịch bảo vệ được phân công', 'COMMITTEE'),
    ('COMMITTEE_DOCUMENT_VIEW', 'Xem hồ sơ bảo vệ được phân công', 'COMMITTEE'),
    ('COMMITTEE_SCORE', 'Đánh giá và chấm điểm hồ sơ được phân công', 'COMMITTEE'),
    ('WORKFLOW_CONFIG', 'Quản lý mẫu, phiên bản và cấu hình quy trình theo đợt', 'CONFIG'),
    ('TEMPLATE_MANAGE', 'Quản lý biểu mẫu và mẫu thông báo', 'CONFIG'),
    ('NOTIFICATION_LOG_VIEW', 'Xem lịch sử gửi thông báo', 'CONFIG'),
    ('USER_MANAGE', 'Quản lý người dùng và phân quyền', 'CONFIG'),
    ('CATALOG_MANAGE', 'Quản lý Khoa, Bộ môn và hệ đào tạo', 'CONFIG'),
    ('SYSTEM_CONFIG', 'Cấu hình hệ thống và email', 'CONFIG'),
    ('AUDIT_VIEW', 'Xem nhật ký thao tác', 'CONFIG')
    ) v(code, name, module)
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, module = EXCLUDED.module, last_modified_date = now();

    -- Replace built-in grants only; leave custom roles and permissions untouched.
    DELETE FROM role_permissions rp USING roles r, permissions p
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
      AND r.role_code IN ('ADMIN', 'LECTURER', 'FACULTY_STAFF', 'COMMITTEE', 'STUDENT')
      AND p.code IN ('DASHBOARD_VIEW', 'NOTIFICATION_VIEW', 'PROFILE_EDIT', 'THESIS_ROUND_VIEW', 'THESIS_ROUND_MANAGE', 'ROUND_PARTICIPANTS_VIEW', 'LECTURER_DIRECTORY_VIEW', 'TOPIC_DIRECTORY_VIEW', 'STUDENT_PROPOSAL', 'STUDENT_PROGRESS_VIEW', 'STUDENT_DOCUMENT_VIEW', 'STUDENT_DEFENSE_VIEW', 'STUDENT_RESULT_VIEW', 'TOPIC_CREATE', 'ADVISOR_REGISTRATION_CONFIRM', 'ADVISOR_STUDENTS_VIEW', 'ADVISOR_DOCUMENT_APPROVE', 'ADVISOR_REVIEW_HISTORY', 'TOPIC_VIEW', 'ADVISOR_ASSIGN', 'THESIS_PROGRESS_VIEW', 'OUTLINE_APPROVE', 'TOPIC_RENAME_APPROVE', 'SIMILARITY_CHECK', 'OVERDUE_TRACK', 'EXCEPTION_HANDLE', 'COMMITTEE_MANAGE', 'DEFENSE_SCHEDULE_MANAGE', 'DEFENSE_RESULTS_VIEW', 'REPORT_VIEW', 'COMMITTEE_MEMBERSHIP_VIEW', 'COMMITTEE_SCHEDULE_VIEW', 'COMMITTEE_DOCUMENT_VIEW', 'COMMITTEE_SCORE', 'WORKFLOW_CONFIG', 'TEMPLATE_MANAGE', 'NOTIFICATION_LOG_VIEW', 'USER_MANAGE', 'CATALOG_MANAGE', 'SYSTEM_CONFIG', 'AUDIT_VIEW', 'TOPIC_APPROVE', 'TOPIC_SUGGEST', 'STUDENT_PROGRESS_REPORT', 'STUDENT_FINAL_SUBMIT');
    INSERT INTO role_permissions (id, role_id, permission_id, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, r.id, p.id, now(), now() FROM (VALUES
    ('ADMIN', 'DASHBOARD_VIEW'),
    ('ADMIN', 'NOTIFICATION_VIEW'),
    ('ADMIN', 'PROFILE_EDIT'),
    ('ADMIN', 'THESIS_ROUND_VIEW'),
    ('ADMIN', 'THESIS_ROUND_MANAGE'),
    ('ADMIN', 'WORKFLOW_CONFIG'),
    ('ADMIN', 'TEMPLATE_MANAGE'),
    ('ADMIN', 'NOTIFICATION_LOG_VIEW'),
    ('ADMIN', 'USER_MANAGE'),
    ('ADMIN', 'CATALOG_MANAGE'),
    ('ADMIN', 'SYSTEM_CONFIG'),
    ('ADMIN', 'AUDIT_VIEW'),
    ('LECTURER', 'DASHBOARD_VIEW'),
    ('LECTURER', 'NOTIFICATION_VIEW'),
    ('LECTURER', 'PROFILE_EDIT'),
    ('LECTURER', 'TOPIC_CREATE'),
    ('LECTURER', 'ADVISOR_REGISTRATION_CONFIRM'),
    ('LECTURER', 'ADVISOR_STUDENTS_VIEW'),
    ('LECTURER', 'ADVISOR_DOCUMENT_APPROVE'),
    ('LECTURER', 'ADVISOR_REVIEW_HISTORY'),
    ('FACULTY_STAFF', 'DASHBOARD_VIEW'),
    ('FACULTY_STAFF', 'NOTIFICATION_VIEW'),
    ('FACULTY_STAFF', 'PROFILE_EDIT'),
    ('FACULTY_STAFF', 'THESIS_ROUND_VIEW'),
    ('FACULTY_STAFF', 'ROUND_PARTICIPANTS_VIEW'),
    ('FACULTY_STAFF', 'TOPIC_VIEW'),
    ('FACULTY_STAFF', 'ADVISOR_ASSIGN'),
    ('FACULTY_STAFF', 'THESIS_PROGRESS_VIEW'),
    ('FACULTY_STAFF', 'OUTLINE_APPROVE'),
    ('FACULTY_STAFF', 'TOPIC_RENAME_APPROVE'),
    ('FACULTY_STAFF', 'SIMILARITY_CHECK'),
    ('FACULTY_STAFF', 'OVERDUE_TRACK'),
    ('FACULTY_STAFF', 'EXCEPTION_HANDLE'),
    ('FACULTY_STAFF', 'COMMITTEE_MANAGE'),
    ('FACULTY_STAFF', 'DEFENSE_SCHEDULE_MANAGE'),
    ('FACULTY_STAFF', 'DEFENSE_RESULTS_VIEW'),
    ('FACULTY_STAFF', 'REPORT_VIEW'),
    ('COMMITTEE', 'DASHBOARD_VIEW'),
    ('COMMITTEE', 'NOTIFICATION_VIEW'),
    ('COMMITTEE', 'PROFILE_EDIT'),
    ('COMMITTEE', 'COMMITTEE_MEMBERSHIP_VIEW'),
    ('COMMITTEE', 'COMMITTEE_SCHEDULE_VIEW'),
    ('COMMITTEE', 'COMMITTEE_DOCUMENT_VIEW'),
    ('COMMITTEE', 'COMMITTEE_SCORE'),
    ('STUDENT', 'DASHBOARD_VIEW'),
    ('STUDENT', 'NOTIFICATION_VIEW'),
    ('STUDENT', 'PROFILE_EDIT'),
    ('STUDENT', 'LECTURER_DIRECTORY_VIEW'),
    ('STUDENT', 'TOPIC_DIRECTORY_VIEW'),
    ('STUDENT', 'STUDENT_PROPOSAL'),
    ('STUDENT', 'STUDENT_PROGRESS_VIEW'),
    ('STUDENT', 'STUDENT_DOCUMENT_VIEW'),
    ('STUDENT', 'STUDENT_DEFENSE_VIEW'),
    ('STUDENT', 'STUDENT_RESULT_VIEW')
    ) v(role_code, permission_code)
    JOIN roles r ON r.role_code = v.role_code JOIN permissions p ON p.code = v.permission_code;

    -- Faculty and committee are supplementary roles of a lecturer. Preserve round scope.
    INSERT INTO user_roles (id, user_id, role_id, thesis_round_id, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, v.user_id, r.id, v.thesis_round_id, now(), now()
    FROM (SELECT DISTINCT ur.user_id, ur.thesis_round_id FROM user_roles ur JOIN roles sr ON sr.id = ur.role_id
          WHERE sr.role_code IN ('FACULTY_STAFF', 'COMMITTEE')) v
    JOIN roles r ON r.role_code = 'LECTURER'
    WHERE NOT EXISTS (SELECT 1 FROM user_roles e WHERE e.user_id = v.user_id AND e.role_id = r.id
                      AND e.thesis_round_id IS NOT DISTINCT FROM v.thesis_round_id);
    UPDATE users u SET user_type = 'LECTURER', last_modified_date = now()
    WHERE u.user_type NOT IN ('ADMIN', 'STUDENT')
      AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                  WHERE ur.user_id = u.id AND r.role_code IN ('FACULTY_STAFF', 'COMMITTEE'));

    -- Retire obsolete baseline menus without deleting IDs or custom entries.
    UPDATE menus SET active = false, last_modified_date = now()
    WHERE code IN ('duyet-de-tai', 'ngan-hang-de-tai', 'nhat-ky-tien-do', 'nop-bao-cao');

    INSERT INTO menus (id, parent_id, code, label, icon, path, sort_order, permission_code, active, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, NULL, v.code, v.label, v.icon, v.path, v.sort_order, v.permission_code, true, now(), now()
    FROM (VALUES
    ('tong-quan', NULL, 'Tổng quan', 'DashboardOutlined', '/dashboard', 1, 'DASHBOARD_VIEW'),
    ('thong-bao', NULL, 'Thông báo', 'MailOutlined', '/notifications', 10, NULL),
    ('tim-giang-vien-de-tai', NULL, 'Tìm giảng viên & Đề tài', 'TeamOutlined', '/directory', 20, NULL),
    ('do-an-cua-toi', NULL, 'Đồ án của tôi', 'BookOutlined', '/student', 30, NULL),
    ('bao-ve-ket-qua', NULL, 'Bảo vệ & Kết quả', 'ScheduleOutlined', '/student/defense', 40, NULL),
    ('ho-so-de-tai-giang-vien', NULL, 'Hồ sơ & Đề tài', 'BookOutlined', '/lecturer', 50, NULL),
    ('huong-dan-do-an', NULL, 'Hướng dẫn đồ án', 'UserSwitchOutlined', '/lecturer/advising', 60, NULL),
    ('dot-do-an', NULL, 'Đợt đồ án', 'CalendarOutlined', '/rounds', 70, NULL),
    ('quan-ly-de-tai', NULL, 'Quản lý đồ án', 'BookOutlined', '/topics', 80, NULL),
    ('ho-so-tien-do', NULL, 'Duyệt hồ sơ', 'CheckSquareOutlined', '/progress', 90, NULL),
    ('hoi-dong', NULL, 'Tổ chức bảo vệ', 'ScheduleOutlined', '/committee', 100, NULL),
    ('cham-bao-ve', NULL, 'Chấm bảo vệ', 'CheckSquareOutlined', '/committee/my', 110, NULL),
    ('cau-hinh-quy-trinh', NULL, 'Cấu hình quy trình', 'ControlOutlined', '/workflow-config', 120, NULL),
    ('bieu-mau-thong-bao', NULL, 'Biểu mẫu & Thông báo', 'MailOutlined', '/templates', 130, NULL),
    ('nguoi-dung-phan-quyen', NULL, 'Người dùng & Phân quyền', 'TeamOutlined', '/users', 140, NULL),
    ('bao-cao-thong-ke', NULL, 'Báo cáo & Thống kê', 'BarChartOutlined', '/reports', 150, NULL),
    ('danh-muc-he-thong', NULL, 'Danh mục hệ thống', 'MenuOutlined', '/catalogs', 160, NULL),
    ('cai-dat-he-thong', NULL, 'Cài đặt hệ thống', 'SettingOutlined', '/settings', 170, NULL),
    ('tai-khoan-ca-nhan', NULL, 'Tài khoản cá nhân', 'UserOutlined', '/account', 180, NULL)
    ) v(code, parent_code, label, icon, path, sort_order, permission_code)
    
    ON CONFLICT (code) DO UPDATE SET parent_id = EXCLUDED.parent_id, label = EXCLUDED.label,
        icon = EXCLUDED.icon, path = EXCLUDED.path, sort_order = EXCLUDED.sort_order,
        permission_code = EXCLUDED.permission_code, active = true, last_modified_date = now();

    INSERT INTO menus (id, parent_id, code, label, icon, path, sort_order, permission_code, active, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, parent.id, v.code, v.label, v.icon, v.path, v.sort_order, v.permission_code, true, now(), now()
    FROM (VALUES
    ('ds-thong-bao', 'thong-bao', 'Danh sách thông báo', 'FileTextOutlined', '/notifications/list', 11, 'NOTIFICATION_VIEW'),
    ('ds-giang-vien', 'tim-giang-vien-de-tai', 'Danh sách giảng viên', 'UserOutlined', '/directory/lecturers', 21, 'LECTURER_DIRECTORY_VIEW'),
    ('ds-de-tai-cong-khai', 'tim-giang-vien-de-tai', 'Danh sách đề tài', 'FileTextOutlined', '/directory/topics', 22, 'TOPIC_DIRECTORY_VIEW'),
    ('dang-ky-de-tai', 'do-an-cua-toi', 'Đăng ký đề tài & GVHD', 'FileTextOutlined', '/student/proposal', 31, 'STUDENT_PROPOSAL'),
    ('tien-trinh-do-an', 'do-an-cua-toi', 'Tiến trình đồ án', 'ClockCircleOutlined', '/student/progress', 32, 'STUDENT_PROGRESS_VIEW'),
    ('ho-so-da-nop', 'do-an-cua-toi', 'Hồ sơ đã nộp', 'FileTextOutlined', '/student/documents', 33, 'STUDENT_DOCUMENT_VIEW'),
    ('lich-bao-ve-sinh-vien', 'bao-ve-ket-qua', 'Lịch bảo vệ', 'FileTextOutlined', '/student/defense/schedule', 41, 'STUDENT_DEFENSE_VIEW'),
    ('ket-qua-sinh-vien', 'bao-ve-ket-qua', 'Kết quả bảo vệ', 'FileTextOutlined', '/student/defense/results', 42, 'STUDENT_RESULT_VIEW'),
    ('ho-so-giang-vien', 'ho-so-de-tai-giang-vien', 'Thông tin giảng viên', 'UserOutlined', '/lecturer/profile', 51, 'TOPIC_CREATE'),
    ('de-tai-cua-toi', 'ho-so-de-tai-giang-vien', 'Đề tài của tôi', 'FileTextOutlined', '/lecturer/topics', 52, 'TOPIC_CREATE'),
    ('dang-ky-cho-xac-nhan', 'huong-dan-do-an', 'Đăng ký chờ xác nhận', 'FileTextOutlined', '/lecturer/advising/registrations', 61, 'ADVISOR_REGISTRATION_CONFIRM'),
    ('sinh-vien-huong-dan', 'huong-dan-do-an', 'Sinh viên hướng dẫn', 'TeamOutlined', '/lecturer/advising/students', 62, 'ADVISOR_STUDENTS_VIEW'),
    ('ho-so-cho-duyet', 'huong-dan-do-an', 'Hồ sơ chờ duyệt', 'CheckSquareOutlined', '/lecturer/advising/documents', 63, 'ADVISOR_DOCUMENT_APPROVE'),
    ('lich-su-duyet', 'huong-dan-do-an', 'Lịch sử duyệt', 'FileTextOutlined', '/lecturer/advising/history', 64, 'ADVISOR_REVIEW_HISTORY'),
    ('nam-hoc', 'dot-do-an', 'Năm học', 'FileTextOutlined', '/rounds/academic-years', 71, 'THESIS_ROUND_MANAGE'),
    ('hoc-ky', 'dot-do-an', 'Học kỳ', 'FileTextOutlined', '/rounds/semesters', 72, 'THESIS_ROUND_MANAGE'),
    ('ds-dot-do-an', 'dot-do-an', 'Danh sách đợt', 'FileTextOutlined', '/rounds/list', 73, 'THESIS_ROUND_VIEW'),
    ('sinh-vien-tham-gia', 'dot-do-an', 'Sinh viên tham gia', 'FileTextOutlined', '/rounds/participants', 74, 'ROUND_PARTICIPANTS_VIEW'),
    ('ds-de-tai', 'quan-ly-de-tai', 'Danh sách đề tài', 'FileTextOutlined', '/topics/all', 81, 'TOPIC_VIEW'),
    ('phan-cong', 'quan-ly-de-tai', 'Phân công hướng dẫn', 'UserSwitchOutlined', '/advisor-assign', 82, 'ADVISOR_ASSIGN'),
    ('ds-ho-so-tien-do', 'quan-ly-de-tai', 'Hồ sơ & Tiến độ', 'ClockCircleOutlined', '/progress/list', 83, 'THESIS_PROGRESS_VIEW'),
    ('duyet-de-cuong', 'ho-so-tien-do', 'Duyệt đề cương', 'FileTextOutlined', '/progress/outline', 91, 'OUTLINE_APPROVE'),
    ('duyet-doi-ten-de-tai', 'ho-so-tien-do', 'Duyệt đổi tên đề tài', 'FileTextOutlined', '/progress/topic-renames', 92, 'TOPIC_RENAME_APPROVE'),
    ('trung-lap', 'ho-so-tien-do', 'Kiểm tra trùng lắp', 'SafetyCertificateOutlined', '/progress/similarity', 93, 'SIMILARITY_CHECK'),
    ('can-thiep-ngoai-le', 'ho-so-tien-do', 'Xử lý quá hạn & Gia hạn', 'ExclamationCircleOutlined', '/progress/exceptions', 94, 'EXCEPTION_HANDLE'),
    ('ds-hoi-dong', 'hoi-dong', 'Danh sách hội đồng', 'FileTextOutlined', '/committee/list', 101, 'COMMITTEE_MANAGE'),
    ('phan-cong-hoi-dong', 'hoi-dong', 'Phân công hội đồng', 'FileTextOutlined', '/committee/assignments', 102, 'COMMITTEE_MANAGE'),
    ('xep-lich-phong', 'hoi-dong', 'Lịch bảo vệ', 'FileTextOutlined', '/committee/schedule', 103, 'DEFENSE_SCHEDULE_MANAGE'),
    ('tong-hop-ket-qua', 'hoi-dong', 'Tổng hợp kết quả', 'FileTextOutlined', '/committee/results', 104, 'DEFENSE_RESULTS_VIEW'),
    ('hoi-dong-cua-toi', 'cham-bao-ve', 'Hội đồng của tôi', 'FileTextOutlined', '/committee/my/list', 111, 'COMMITTEE_MEMBERSHIP_VIEW'),
    ('lich-bao-ve-phan-cong', 'cham-bao-ve', 'Lịch bảo vệ được phân công', 'FileTextOutlined', '/committee/my/schedule', 112, 'COMMITTEE_SCHEDULE_VIEW'),
    ('ho-so-bao-ve', 'cham-bao-ve', 'Hồ sơ bảo vệ', 'FileTextOutlined', '/committee/my/documents', 113, 'COMMITTEE_DOCUMENT_VIEW'),
    ('cham-diem-hoi-dong', 'cham-bao-ve', 'Đánh giá & Chấm điểm', 'FileTextOutlined', '/committee/scoring', 114, 'COMMITTEE_SCORE'),
    ('mau-quy-trinh', 'cau-hinh-quy-trinh', 'Mẫu quy trình', 'FileTextOutlined', '/workflow-config/templates', 121, 'WORKFLOW_CONFIG'),
    ('phien-ban-quy-trinh', 'cau-hinh-quy-trinh', 'Phiên bản quy trình', 'FileTextOutlined', '/workflow-config/versions', 122, 'WORKFLOW_CONFIG'),
    ('cau-hinh-dot', 'cau-hinh-quy-trinh', 'Cấu hình áp dụng cho đợt', 'FileTextOutlined', '/workflow-config/rounds', 123, 'WORKFLOW_CONFIG'),
    ('mau-ho-so', 'bieu-mau-thong-bao', 'Mẫu hồ sơ', 'FileTextOutlined', '/templates/documents', 131, 'TEMPLATE_MANAGE'),
    ('mau-thong-bao', 'bieu-mau-thong-bao', 'Mẫu thông báo', 'FileTextOutlined', '/templates/notifications', 132, 'TEMPLATE_MANAGE'),
    ('lich-su-gui-thong-bao', 'bieu-mau-thong-bao', 'Lịch sử gửi thông báo', 'FileTextOutlined', '/templates/delivery-history', 133, 'NOTIFICATION_LOG_VIEW'),
    ('users', 'nguoi-dung-phan-quyen', 'Tài khoản người dùng', 'UserOutlined', '/admin/users', 141, 'USER_MANAGE'),
    ('roles', 'nguoi-dung-phan-quyen', 'Nhóm vai trò', 'SafetyCertificateOutlined', '/admin/roles', 142, 'USER_MANAGE'),
    ('menus', 'nguoi-dung-phan-quyen', 'Menu & URL', 'MenuOutlined', '/admin/menus', 144, 'USER_MANAGE'),
    ('bao-cao-tien-do', 'bao-cao-thong-ke', 'Tiến độ theo đợt', 'FileTextOutlined', '/reports/progress', 151, 'REPORT_VIEW'),
    ('theo-doi-tre-han', 'bao-cao-thong-ke', 'Hồ sơ quá hạn', 'FileTextOutlined', '/progress/overdue', 152, 'OVERDUE_TRACK'),
    ('bao-cao-ket-qua', 'bao-cao-thong-ke', 'Kết quả đồ án', 'FileTextOutlined', '/reports/results', 153, 'REPORT_VIEW'),
    ('khoa-bo-mon', 'danh-muc-he-thong', 'Khoa / Bộ môn', 'FileTextOutlined', '/catalogs/departments', 161, 'CATALOG_MANAGE'),
    ('he-dao-tao', 'danh-muc-he-thong', 'Hệ đào tạo', 'FileTextOutlined', '/catalogs/training-systems', 162, 'CATALOG_MANAGE'),
    ('cau-hinh-chung', 'cai-dat-he-thong', 'Cấu hình chung', 'FileTextOutlined', '/settings/general', 171, 'SYSTEM_CONFIG'),
    ('cau-hinh-email', 'cai-dat-he-thong', 'Cấu hình email', 'MailOutlined', '/settings/email', 172, 'SYSTEM_CONFIG'),
    ('nhat-ky-thao-tac', 'cai-dat-he-thong', 'Nhật ký thao tác', 'FileTextOutlined', '/settings/audit', 173, 'AUDIT_VIEW'),
    ('thong-tin-ca-nhan', 'tai-khoan-ca-nhan', 'Thông tin cá nhân', 'FileTextOutlined', '/account/profile', 181, 'PROFILE_EDIT'),
    ('doi-mat-khau', 'tai-khoan-ca-nhan', 'Đổi mật khẩu', 'KeyOutlined', '/account/password', 182, 'PROFILE_EDIT')
    ) v(code, parent_code, label, icon, path, sort_order, permission_code)
    JOIN menus parent ON parent.code = v.parent_code
    ON CONFLICT (code) DO UPDATE SET parent_id = EXCLUDED.parent_id, label = EXCLUDED.label,
        icon = EXCLUDED.icon, path = EXCLUDED.path, sort_order = EXCLUDED.sort_order,
        permission_code = EXCLUDED.permission_code, active = true, last_modified_date = now();
    INSERT INTO app_seed_versions (version) VALUES ('role-menus-v2');
END
$seed$;

-- Permission layers v3-v5
-- Menu access includes viewing. Actions are a separate supported capability catalogue.
ALTER TABLE permissions ADD COLUMN IF NOT EXISTS menu_code varchar(100);
ALTER TABLE permissions ADD COLUMN IF NOT EXISTS action varchar(30);
ALTER TABLE permissions ADD COLUMN IF NOT EXISTS enabled boolean NOT NULL DEFAULT true;
DO $seed$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004, 3);
    IF EXISTS (SELECT 1 FROM app_seed_versions WHERE version = 'permission-layers-v3') OR EXISTS (SELECT 1 FROM app_seed_versions WHERE version = 'role-rbac-v6') THEN RETURN; END IF;

    INSERT INTO permissions (id, code, name, module, menu_code, action, enabled, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, 'VIEW_' || upper(replace(m.code, '-', '_')), 'Truy cập / Xem: ' || m.label,
        'MENU', m.code, 'VIEW', true, now(), now()
    FROM menus m WHERE m.active AND NOT EXISTS (SELECT 1 FROM menus c WHERE c.parent_id=m.id AND c.active)
    ON CONFLICT (code) DO UPDATE SET menu_code=EXCLUDED.menu_code, action='VIEW', enabled=true;

    -- Preserve access from the previous baseline before changing each menu's access code.
    INSERT INTO role_permissions (id, role_id, permission_id, created_date, last_modified_date)
    SELECT DISTINCT gen_random_uuid()::text, rp.role_id, view_permission.id, now(), now()
    FROM menus m JOIN permissions old_permission ON old_permission.code=m.permission_code
    JOIN role_permissions rp ON rp.permission_id=old_permission.id
    JOIN permissions view_permission ON view_permission.menu_code=m.code AND view_permission.action='VIEW'
    WHERE m.active AND NOT EXISTS (SELECT 1 FROM role_permissions existing
        WHERE existing.role_id=rp.role_id AND existing.permission_id=view_permission.id);

    UPDATE menus m SET permission_code=p.code, last_modified_date=now()
    FROM permissions p WHERE p.menu_code=m.code AND p.action='VIEW' AND m.active;

    -- Only actions implemented by current RBAC APIs can be configured here.
    INSERT INTO permissions (id, code, name, module, menu_code, action, enabled, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, v.code, v.name, 'RBAC', v.menu_code, v.action, true, now(), now()
    FROM (VALUES
        ('USERS_CREATE', 'Tạo tài khoản / vai trò', 'users', 'CREATE'),
        ('USERS_UPDATE', 'Thêm thành viên vào vai trò', 'users', 'UPDATE'),
        ('USERS_DELETE', 'Bỏ thành viên khỏi vai trò', 'users', 'DELETE'),
        ('ROLES_UPDATE', 'Lưu quyền truy cập menu', 'roles', 'UPDATE'),
        ('MENUS_CREATE', 'Thêm menu', 'menus', 'CREATE'),
        ('MENUS_UPDATE', 'Cấu hình menu và quyền được hỗ trợ', 'menus', 'UPDATE'),
        ('MENUS_DELETE', 'Xóa menu', 'menus', 'DELETE')
    ) v(code, name, menu_code, action)
    ON CONFLICT (code) DO UPDATE SET menu_code=EXCLUDED.menu_code, action=EXCLUDED.action, enabled=true;

    INSERT INTO role_permissions (id, role_id, permission_id, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, legacy.role_id, p.id, now(), now()
    FROM role_permissions legacy JOIN permissions old ON old.id=legacy.permission_id AND old.code='USER_MANAGE'
    CROSS JOIN permissions p WHERE p.module='RBAC' AND p.menu_code IS NOT NULL AND p.action <> 'VIEW'
      AND NOT EXISTS (SELECT 1 FROM role_permissions existing WHERE existing.role_id=legacy.role_id AND existing.permission_id=p.id);

    UPDATE menus SET label=CASE code
        WHEN 'users' THEN 'Vai trò & Thành viên'
        WHEN 'roles' THEN 'Quyền truy cập menu'
        WHEN 'permissions' THEN 'Quyền thao tác'
        WHEN 'menus' THEN 'Cấu hình menu & Quyền' END
    WHERE code IN ('users','roles','permissions','menus');
    UPDATE permissions p SET name='Truy cập / Xem: ' || m.label FROM menus m
    WHERE p.menu_code=m.code AND p.action='VIEW';
    INSERT INTO app_seed_versions (version) VALUES ('permission-layers-v3');
END $seed$;

DO $seed$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004, 4);
    IF EXISTS (SELECT 1 FROM app_seed_versions WHERE version = 'permission-layers-v4-menu-actions') OR EXISTS (SELECT 1 FROM app_seed_versions WHERE version = 'role-rbac-v6') THEN RETURN; END IF;

    INSERT INTO permissions (id, code, name, module, menu_code, action, enabled, created_date, last_modified_date)
    SELECT gen_random_uuid()::text,
        upper(replace(m.code, '-', '_')) || '_' || a.action,
        a.label || ': ' || m.label,
        'MENU_ACTION',
        m.code,
        a.action,
        true,
        now(),
        now()
    FROM menus m
    CROSS JOIN (VALUES
        ('CREATE', 'Tạo mới'),
        ('UPDATE', 'Cập nhật'),
        ('DELETE', 'Xóa'),
        ('APPROVE', 'Duyệt'),
        ('EXPORT', 'Xuất file')
    ) a(action, label)
    WHERE m.active
      AND NOT EXISTS (SELECT 1 FROM menus child WHERE child.parent_id = m.id AND child.active)
      AND NOT EXISTS (
          SELECT 1 FROM permissions existing
          WHERE existing.menu_code = m.code AND existing.action = a.action
      )
    ON CONFLICT (code) DO NOTHING;

    INSERT INTO role_permissions (id, role_id, permission_id, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, r.id, p.id, now(), now()
    FROM roles r
    JOIN permissions p ON p.menu_code IS NOT NULL AND p.action <> 'VIEW' AND p.enabled
    WHERE r.role_code = 'ADMIN'
      AND NOT EXISTS (
          SELECT 1 FROM role_permissions existing
          WHERE existing.role_id = r.id AND existing.permission_id = p.id
      );

    INSERT INTO app_seed_versions (version) VALUES ('permission-layers-v4-menu-actions');
END $seed$;

DO $seed$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004, 5);
    IF EXISTS (SELECT 1 FROM app_seed_versions WHERE version = 'permission-layers-v5-user-actions') OR EXISTS (SELECT 1 FROM app_seed_versions WHERE version = 'role-rbac-v6') THEN RETURN; END IF;

    CREATE TABLE IF NOT EXISTS user_permissions (
        id varchar(36) PRIMARY KEY,
        user_id varchar(36) NOT NULL,
        permission_id varchar(36) NOT NULL,
        created_by varchar(50),
        created_date timestamp,
        last_modified_by varchar(50),
        last_modified_date timestamp
    );

    CREATE UNIQUE INDEX IF NOT EXISTS ux_user_permissions_user_permission
        ON user_permissions (user_id, permission_id);

    INSERT INTO user_permissions (id, user_id, permission_id, created_date, last_modified_date)
    SELECT gen_random_uuid()::text, ur.user_id, rp.permission_id, now(), now()
    FROM user_roles ur
    JOIN role_permissions rp ON rp.role_id = ur.role_id
    JOIN permissions p ON p.id = rp.permission_id
    WHERE p.action IS NOT NULL
      AND p.action <> 'VIEW'
      AND p.enabled = true
      AND NOT EXISTS (
          SELECT 1 FROM user_permissions existing
          WHERE existing.user_id = ur.user_id AND existing.permission_id = rp.permission_id
      );

    DELETE FROM role_permissions rp
    USING permissions p
    WHERE p.id = rp.permission_id
      AND p.action IS NOT NULL
      AND p.action <> 'VIEW';

    INSERT INTO app_seed_versions (version) VALUES ('permission-layers-v5-user-actions');
END $seed$;

-- Upgrade: migrate_role_rbac.sql
-- Forward-only migration. Historical seed files are left intact for existing installations.
-- Runs in the caller's transaction; abort rather than silently delete invalid references.
DO $migration$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004, 6);
    IF EXISTS (SELECT 1 FROM app_seed_versions WHERE version='role-rbac-v6') THEN RETURN; END IF;

    -- Old seed/test data can contain memberships whose account no longer exists.
    -- Preserve those rows for inspection before enforcing referential integrity.
    CREATE TABLE IF NOT EXISTS rbac_migration_quarantine (
        id bigserial PRIMARY KEY, source_table varchar(100) NOT NULL,
        payload jsonb NOT NULL, archived_at timestamptz NOT NULL DEFAULT now()
    );
    INSERT INTO rbac_migration_quarantine(source_table,payload)
    SELECT 'user_roles',to_jsonb(ur) FROM user_roles ur
    WHERE NOT EXISTS(SELECT 1 FROM users u WHERE u.id=ur.user_id)
       OR NOT EXISTS(SELECT 1 FROM roles r WHERE r.id=ur.role_id);
    DELETE FROM user_roles ur WHERE NOT EXISTS(SELECT 1 FROM users u WHERE u.id=ur.user_id)
       OR NOT EXISTS(SELECT 1 FROM roles r WHERE r.id=ur.role_id);
    INSERT INTO rbac_migration_quarantine(source_table,payload)
    SELECT 'role_permissions',to_jsonb(rp) FROM role_permissions rp
    WHERE NOT EXISTS(SELECT 1 FROM roles r WHERE r.id=rp.role_id)
       OR NOT EXISTS(SELECT 1 FROM permissions p WHERE p.id=rp.permission_id);
    DELETE FROM role_permissions rp WHERE NOT EXISTS(SELECT 1 FROM roles r WHERE r.id=rp.role_id)
       OR NOT EXISTS(SELECT 1 FROM permissions p WHERE p.id=rp.permission_id);

    UPDATE menus SET label=CASE code WHEN 'users' THEN 'Người dùng'
        WHEN 'roles' THEN 'Vai trò & Phân quyền' WHEN 'permissions' THEN 'Danh mục quyền'
        WHEN 'menus' THEN 'Cấu hình menu' END WHERE code IN ('users','roles','permissions','menus');

    -- Only capabilities implemented by the application may be enabled.
    UPDATE permissions SET enabled=false WHERE action IS NOT NULL AND action <> 'VIEW';
    INSERT INTO permissions(id,code,name,module,menu_code,action,enabled,created_date,last_modified_date)
    SELECT gen_random_uuid()::text,v.code,v.name,'RBAC',v.menu_code,v.action,true,now(),now()
    FROM (VALUES
        ('USERS_CREATE','Tạo tài khoản','users','CREATE'),
        ('USERS_UPDATE','Cập nhật / khóa tài khoản','users','UPDATE'),
        ('USERS_ASSIGN_ROLE','Gán / bỏ vai trò','users','ASSIGN_ROLE'),
        ('ROLES_CREATE','Tạo vai trò','roles','CREATE'),
        ('ROLES_UPDATE','Sửa vai trò và phân quyền','roles','UPDATE'),
        ('ROLES_DELETE','Xóa vai trò chưa có thành viên','roles','DELETE'),
        ('MENUS_CREATE','Thêm menu','menus','CREATE'),
        ('MENUS_UPDATE','Sửa menu','menus','UPDATE'),
        ('MENUS_DELETE','Xóa menu','menus','DELETE')
    ) v(code,name,menu_code,action)
    ON CONFLICT(code) DO UPDATE SET name=excluded.name,module=excluded.module,
        menu_code=excluded.menu_code,action=excluded.action,enabled=true,last_modified_date=now();
    UPDATE permissions p SET name='Truy cập / Xem: '||m.label FROM menus m
        WHERE p.menu_code=m.code AND p.action='VIEW';

    -- Preserve direct supported grants as explicit, editable roles, never grant them
    -- to an existing shared role (which would escalate other members).
    INSERT INTO roles(id,role_code,role_name,created_date,last_modified_date)
    SELECT gen_random_uuid()::text,'MIGRATED_'||replace(u.id,'-',''),
        'Quyền chuyển đổi: '||left(u.full_name,70),now(),now()
    FROM users u WHERE EXISTS(SELECT 1 FROM user_permissions up JOIN permissions p ON p.id=up.permission_id
        WHERE up.user_id=u.id AND p.enabled)
        AND NOT EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
            WHERE ur.user_id=u.id AND r.role_code='ADMIN' AND ur.thesis_round_id IS NULL)
    ON CONFLICT(role_code) DO NOTHING;
    INSERT INTO role_permissions(id,role_id,permission_id,created_date,last_modified_date)
    SELECT DISTINCT gen_random_uuid()::text,r.id,p.id,now(),now()
    FROM users u JOIN roles r ON r.role_code='MIGRATED_'||replace(u.id,'-','')
    JOIN user_permissions up ON up.user_id=u.id JOIN permissions p ON p.id=up.permission_id
    WHERE p.enabled AND NOT EXISTS(SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
    -- Carry only global menu access; scoped VIEW remains on its scoped membership.
    INSERT INTO role_permissions(id,role_id,permission_id,created_date,last_modified_date)
    SELECT gen_random_uuid()::text,r.id,p.id,now(),now()
    FROM users u JOIN roles r ON r.role_code='MIGRATED_'||replace(u.id,'-','')
    JOIN permissions p ON p.action='VIEW' AND p.enabled
    WHERE EXISTS(SELECT 1 FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id
        WHERE ur.user_id=u.id AND ur.thesis_round_id IS NULL AND rp.permission_id=p.id)
      AND NOT EXISTS(SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
    INSERT INTO user_roles(id,user_id,role_id,thesis_round_id,created_date,last_modified_date)
    SELECT gen_random_uuid()::text,u.id,r.id,NULL,now(),now()
    FROM users u JOIN roles r ON r.role_code='MIGRATED_'||replace(u.id,'-','')
    WHERE NOT EXISTS(SELECT 1 FROM user_roles ur WHERE ur.user_id=u.id AND ur.role_id=r.id AND ur.thesis_round_id IS NULL);

    -- Administrator is a reserved role with all enabled capabilities.
    INSERT INTO role_permissions(id,role_id,permission_id,created_date,last_modified_date)
    SELECT gen_random_uuid()::text,r.id,p.id,now(),now() FROM roles r CROSS JOIN permissions p
    WHERE r.role_code='ADMIN' AND p.enabled
      AND NOT EXISTS(SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

    CREATE TABLE IF NOT EXISTS thesis_rounds (
        id varchar(36) PRIMARY KEY, code varchar(100) UNIQUE NOT NULL,
        name varchar(150) NOT NULL, active boolean NOT NULL DEFAULT true,
        created_by varchar(50),created_date timestamp,last_modified_by varchar(50),last_modified_date timestamp
    );
    INSERT INTO thesis_rounds(id,code,name,created_date,last_modified_date)
    SELECT DISTINCT thesis_round_id,thesis_round_id,'Đợt chuyển đổi: '||thesis_round_id,now(),now()
        FROM user_roles WHERE thesis_round_id IS NOT NULL ON CONFLICT(id) DO NOTHING;
    UPDATE users SET status='ACTIVE' WHERE status IS NULL;
    UPDATE users SET user_type='STUDENT' WHERE user_type IS NULL;
    -- De-duplicate only identical grants/memberships, keeping the original surviving ID.
    DELETE FROM role_permissions a USING role_permissions b
        WHERE a.role_id=b.role_id AND a.permission_id=b.permission_id AND a.id>b.id;
    DELETE FROM user_roles a USING user_roles b WHERE a.user_id=b.user_id AND a.role_id=b.role_id
        AND a.thesis_round_id IS NOT DISTINCT FROM b.thesis_round_id AND a.id>b.id;
    CREATE UNIQUE INDEX IF NOT EXISTS ux_role_permissions_pair ON role_permissions(role_id,permission_id);
    CREATE UNIQUE INDEX IF NOT EXISTS ux_user_roles_global ON user_roles(user_id,role_id) WHERE thesis_round_id IS NULL;
    CREATE UNIQUE INDEX IF NOT EXISTS ux_user_roles_round ON user_roles(user_id,role_id,thesis_round_id) WHERE thesis_round_id IS NOT NULL;
    CREATE INDEX IF NOT EXISTS ix_user_roles_role ON user_roles(role_id);
    CREATE INDEX IF NOT EXISTS ix_role_permissions_permission ON role_permissions(permission_id);
    CREATE INDEX IF NOT EXISTS ix_permissions_menu ON permissions(menu_code);
    CREATE INDEX IF NOT EXISTS ix_menus_parent ON menus(parent_id);
    ALTER TABLE user_roles ADD CONSTRAINT fk_user_roles_user FOREIGN KEY(user_id) REFERENCES users(id);
    ALTER TABLE user_roles ADD CONSTRAINT fk_user_roles_role FOREIGN KEY(role_id) REFERENCES roles(id);
    ALTER TABLE user_roles ADD CONSTRAINT fk_user_roles_round FOREIGN KEY(thesis_round_id) REFERENCES thesis_rounds(id);
    ALTER TABLE role_permissions ADD CONSTRAINT fk_role_permissions_role FOREIGN KEY(role_id) REFERENCES roles(id);
    ALTER TABLE role_permissions ADD CONSTRAINT fk_role_permissions_permission FOREIGN KEY(permission_id) REFERENCES permissions(id);
    ALTER TABLE menus ADD CONSTRAINT fk_menus_parent FOREIGN KEY(parent_id) REFERENCES menus(id);
    ALTER TABLE menus ADD CONSTRAINT fk_menus_permission FOREIGN KEY(permission_code) REFERENCES permissions(code);
    ALTER TABLE permissions ADD CONSTRAINT fk_permissions_menu FOREIGN KEY(menu_code) REFERENCES menus(code) DEFERRABLE INITIALLY DEFERRED;
    ALTER TABLE users ALTER COLUMN status SET DEFAULT 'ACTIVE';
    ALTER TABLE users ALTER COLUMN status SET NOT NULL;
    ALTER TABLE users ADD CONSTRAINT ck_users_status CHECK(status IN ('ACTIVE','INACTIVE'));
    ALTER TABLE permissions ADD CONSTRAINT ck_permission_action CHECK(action IS NULL OR action IN ('VIEW','CREATE','UPDATE','DELETE','APPROVE','EXPORT','ASSIGN_ROLE'));

    -- Retain a read-only historical copy for recovery, removed from the live model.
    ALTER TABLE user_permissions RENAME TO legacy_user_permissions_v5;
    ALTER TABLE users DROP COLUMN IF EXISTS password;
    ALTER TABLE users DROP COLUMN IF EXISTS username;
    INSERT INTO app_seed_versions(version) VALUES('role-rbac-v6');
END $migration$;

-- Upgrade: remove_permission_catalogue.sql
-- Remove the retired catalogue screen, retaining the RBAC permission model.
DO $migration$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004, 7);
    IF EXISTS (SELECT 1 FROM app_seed_versions WHERE version='role-rbac-v7-remove-catalogue') THEN RETURN; END IF;
    IF EXISTS (SELECT 1 FROM menus child JOIN menus parent ON child.parent_id=parent.id
        WHERE parent.code='permissions' OR parent.path='/admin/permissions') THEN
        RAISE EXCEPTION 'Cannot remove catalogue while it contains custom child menus';
    END IF;
    DELETE FROM role_permissions rp USING permissions p WHERE rp.permission_id=p.id
        AND (p.menu_code='permissions' OR p.code='VIEW_PERMISSIONS' OR p.code LIKE 'PERMISSIONS\_%' ESCAPE '\');
    DELETE FROM menus WHERE code='permissions' OR path='/admin/permissions';
    DELETE FROM permissions WHERE menu_code='permissions' OR code='VIEW_PERMISSIONS' OR code LIKE 'PERMISSIONS\_%' ESCAPE '\';
    INSERT INTO app_seed_versions(version) VALUES('role-rbac-v7-remove-catalogue');
END $migration$;

-- Upgrade: compact_rbac_catalogue.sql
-- Retire unused historical grants without changing any current menu access.
-- Keep historical seeds immutable; this migration also runs on fresh databases.
DO $migration$
DECLARE
    retired_ids text[];
    retired_menu_ids text[];
BEGIN
    PERFORM pg_advisory_xact_lock(20261004, 8);
    IF EXISTS (SELECT 1 FROM app_seed_versions WHERE version='role-rbac-v8-compact-catalogue') THEN RETURN; END IF;

    SELECT coalesce(array_agg(id::text), ARRAY[]::text[]) INTO retired_menu_ids
    FROM menus WHERE NOT active AND code IN ('duyet-de-tai','ngan-hang-de-tai','nhat-ky-tien-do','nop-bao-cao');
    IF EXISTS (SELECT 1 FROM menus WHERE parent_id=ANY(retired_menu_ids)) THEN
        RAISE EXCEPTION 'Retired menus still have children; review before cleanup';
    END IF;

    SELECT coalesce(array_agg(id::text), ARRAY[]::text[]) INTO retired_ids
    FROM permissions WHERE
        (NOT enabled AND (module='MENU_ACTION' OR code='USERS_DELETE'))
        OR (action IS NULL AND code IN (
            'ADVISOR_ASSIGN','ADVISOR_DOCUMENT_APPROVE','ADVISOR_REGISTRATION_CONFIRM',
            'ADVISOR_REVIEW_HISTORY','ADVISOR_STUDENTS_VIEW','AUDIT_VIEW','CATALOG_MANAGE',
            'COMMITTEE_DOCUMENT_VIEW','COMMITTEE_MANAGE','COMMITTEE_MEMBERSHIP_VIEW',
            'COMMITTEE_SCHEDULE_VIEW','COMMITTEE_SCORE','DASHBOARD_VIEW','DEFENSE_RESULTS_VIEW',
            'DEFENSE_SCHEDULE_MANAGE','EXCEPTION_HANDLE','LECTURER_DIRECTORY_VIEW',
            'NOTIFICATION_LOG_VIEW','NOTIFICATION_VIEW','OUTLINE_APPROVE','OVERDUE_TRACK',
            'PROFILE_EDIT','REPORT_VIEW','ROUND_PARTICIPANTS_VIEW','SIMILARITY_CHECK',
            'STUDENT_DEFENSE_VIEW','STUDENT_DOCUMENT_VIEW','STUDENT_FINAL_SUBMIT',
            'STUDENT_PROGRESS_REPORT','STUDENT_PROGRESS_VIEW','STUDENT_PROPOSAL',
            'STUDENT_RESULT_VIEW','SYSTEM_CONFIG','TEMPLATE_MANAGE','THESIS_PROGRESS_VIEW',
            'THESIS_ROUND_MANAGE','THESIS_ROUND_VIEW','TOPIC_APPROVE','TOPIC_CREATE',
            'TOPIC_DIRECTORY_VIEW','TOPIC_RENAME_APPROVE','TOPIC_SUGGEST','TOPIC_VIEW',
            'USER_MANAGE','WORKFLOW_CONFIG'));

    IF EXISTS (SELECT 1 FROM menus m JOIN permissions p ON p.code=m.permission_code
        WHERE p.id=ANY(retired_ids) AND NOT (m.id=ANY(retired_menu_ids))) THEN
        RAISE EXCEPTION 'A retained menu still uses a historical permission; review before cleanup';
    END IF;
    IF EXISTS (SELECT 1 FROM permissions p JOIN menus m ON m.code=p.menu_code
        WHERE m.id=ANY(retired_menu_ids) AND NOT (p.id=ANY(retired_ids))) THEN
        RAISE EXCEPTION 'A retired menu has custom permissions; review before cleanup';
    END IF;

    -- Reuse the existing history archive rather than leave duplicate live catalogues.
    INSERT INTO rbac_migration_quarantine(source_table,payload)
    SELECT 'permissions',to_jsonb(p) FROM permissions p WHERE p.id=ANY(retired_ids);
    INSERT INTO rbac_migration_quarantine(source_table,payload)
    SELECT 'role_permissions',to_jsonb(rp) FROM role_permissions rp WHERE rp.permission_id=ANY(retired_ids);
    INSERT INTO rbac_migration_quarantine(source_table,payload)
    SELECT 'menus',to_jsonb(m) FROM menus m WHERE m.id=ANY(retired_menu_ids);

    DELETE FROM role_permissions WHERE permission_id=ANY(retired_ids);
    DELETE FROM menus WHERE id=ANY(retired_menu_ids);
    DELETE FROM permissions WHERE id=ANY(retired_ids);
    INSERT INTO app_seed_versions(version) VALUES('role-rbac-v8-compact-catalogue');
END $migration$;

-- Upgrade: migrate_actor_rbac.sql
-- Three account types, five fixed roles, bounded permission assignment.
DO $migration$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,9);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='actor-rbac-v9') THEN RETURN; END IF;
    ALTER TABLE users ADD COLUMN IF NOT EXISTS username varchar(50);
    ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash varchar(255);
    IF to_regclass('public.security') IS NOT NULL THEN
        IF EXISTS(SELECT 1 FROM security s LEFT JOIN users u ON u.id=s.user_id WHERE u.id IS NULL) THEN
            RAISE EXCEPTION 'Orphan credentials require manual reconciliation';
        END IF;
        IF EXISTS(SELECT 1 FROM users u JOIN security s ON s.user_id=u.id
            WHERE (u.username IS NOT NULL AND u.username<>s.username)
               OR (u.password_hash IS NOT NULL AND u.password_hash<>s.password_hash)) THEN
            RAISE EXCEPTION 'Conflicting credentials require manual reconciliation';
        END IF;
        UPDATE users u SET username=s.username,password_hash=s.password_hash FROM security s WHERE s.user_id=u.id;
    END IF;
    IF EXISTS(SELECT 1 FROM users WHERE username IS NULL OR password_hash IS NULL OR user_type NOT IN ('ADMIN','LECTURER','STUDENT')) THEN
        RAISE EXCEPTION 'Missing credentials or unsupported account types; migration aborted without deleting data';
    END IF;
    IF EXISTS(SELECT 1 FROM roles WHERE role_code NOT IN ('ADMIN','LECTURER','STUDENT','FACULTY_STAFF','COMMITTEE')) THEN
        RAISE EXCEPTION 'Custom roles require explicit reconciliation before enabling fixed roles';
    END IF;
    ALTER TABLE users ALTER COLUMN username SET NOT NULL;
    ALTER TABLE users ALTER COLUMN password_hash SET NOT NULL;
    CREATE UNIQUE INDEX IF NOT EXISTS ux_users_username ON users(username);
    ALTER TABLE users ADD CONSTRAINT ck_users_actor CHECK(user_type IN ('ADMIN','LECTURER','STUDENT'));
    ALTER TABLE roles ADD CONSTRAINT ck_roles_fixed CHECK(role_code IN ('ADMIN','LECTURER','STUDENT','FACULTY_STAFF','COMMITTEE'));
    ALTER TABLE roles ADD COLUMN IF NOT EXISTS permissions_version bigint NOT NULL DEFAULT 0;
    UPDATE roles SET permissions_version=0 WHERE permissions_version IS NULL;
    ALTER TABLE roles ALTER COLUMN permissions_version SET DEFAULT 0;
    ALTER TABLE roles ALTER COLUMN permissions_version SET NOT NULL;
    CREATE TABLE IF NOT EXISTS role_allowed_permissions (
        id varchar(36) PRIMARY KEY,role_id varchar(36) NOT NULL REFERENCES roles(id),
        permission_id varchar(36) NOT NULL REFERENCES permissions(id),
        created_by varchar(50),created_date timestamp,last_modified_by varchar(50),last_modified_date timestamp,
        CONSTRAINT ux_role_allowed_pair UNIQUE(role_id,permission_id)
    );
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='fk_allowed_role') THEN
        ALTER TABLE role_allowed_permissions ADD CONSTRAINT fk_allowed_role FOREIGN KEY(role_id) REFERENCES roles(id);
        ALTER TABLE role_allowed_permissions ADD CONSTRAINT fk_allowed_permission FOREIGN KEY(permission_id) REFERENCES permissions(id);
    END IF;
    -- Creation/deletion of roles is no longer part of the fixed-role model.
    INSERT INTO rbac_migration_quarantine(source_table,payload)
        SELECT 'role_permissions',to_jsonb(rp) FROM role_permissions rp JOIN permissions p ON p.id=rp.permission_id
        WHERE p.code IN ('ROLES_CREATE','ROLES_DELETE');
    DELETE FROM role_permissions rp USING permissions p WHERE p.id=rp.permission_id AND p.code IN ('ROLES_CREATE','ROLES_DELETE');
    INSERT INTO rbac_migration_quarantine(source_table,payload)
        SELECT 'permissions',to_jsonb(p) FROM permissions p WHERE p.code IN ('ROLES_CREATE','ROLES_DELETE');
    DELETE FROM permissions WHERE code IN ('ROLES_CREATE','ROLES_DELETE');

    WITH RECURSIVE tree AS (
        SELECT id,code,code AS root_code FROM menus WHERE parent_id IS NULL
        UNION ALL SELECT m.id,m.code,t.root_code FROM menus m JOIN tree t ON m.parent_id=t.id
    )
    INSERT INTO role_allowed_permissions(id,role_id,permission_id,created_date,last_modified_date)
    SELECT gen_random_uuid()::text,r.id,p.id,now(),now() FROM roles r CROSS JOIN permissions p
    LEFT JOIN tree t ON t.code=p.menu_code WHERE p.enabled AND (
        r.role_code='ADMIN' OR (p.action='VIEW' AND (
            t.root_code IN ('tong-quan','thong-bao','tai-khoan-ca-nhan')
            OR (r.role_code='STUDENT' AND t.root_code IN ('tim-giang-vien-de-tai','do-an-cua-toi','bao-ve-ket-qua'))
            OR (r.role_code='LECTURER' AND t.root_code IN ('ho-so-de-tai-giang-vien','huong-dan-do-an'))
            OR (r.role_code='FACULTY_STAFF' AND t.root_code IN ('dot-do-an','quan-ly-de-tai','ho-so-tien-do','hoi-dong','bao-cao-thong-ke'))
            OR (r.role_code='COMMITTEE' AND (t.root_code='cham-bao-ve' OR t.code='cham-diem-hoi-dong'))
        ))) ON CONFLICT(role_id,permission_id) DO NOTHING;

    INSERT INTO rbac_migration_quarantine(source_table,payload)
    SELECT 'role_permissions',to_jsonb(rp) FROM role_permissions rp WHERE NOT EXISTS(
        SELECT 1 FROM role_allowed_permissions a WHERE a.role_id=rp.role_id AND a.permission_id=rp.permission_id);
    DELETE FROM role_permissions rp WHERE NOT EXISTS(
        SELECT 1 FROM role_allowed_permissions a WHERE a.role_id=rp.role_id AND a.permission_id=rp.permission_id);
    ALTER TABLE role_permissions ADD CONSTRAINT fk_grant_within_allowed
        FOREIGN KEY(role_id,permission_id) REFERENCES role_allowed_permissions(role_id,permission_id);

    IF EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id JOIN users u ON u.id=ur.user_id
        WHERE (r.role_code IN ('LECTURER','FACULTY_STAFF','COMMITTEE') AND u.user_type<>'LECTURER')
        OR (r.role_code='STUDENT' AND u.user_type<>'STUDENT')
        OR (r.role_code='ADMIN' AND (u.user_type<>'ADMIN' OR ur.thesis_round_id IS NOT NULL))) THEN
        RAISE EXCEPTION 'Existing memberships conflict with account types';
    END IF;
    -- Every account receives its base role; supplementary assignments are preserved.
    INSERT INTO user_roles(id,user_id,role_id,created_date,last_modified_date)
    SELECT gen_random_uuid()::text,u.id,r.id,now(),now() FROM users u JOIN roles r ON r.role_code=u.user_type
    WHERE NOT EXISTS(SELECT 1 FROM user_roles ur WHERE ur.user_id=u.id AND ur.role_id=r.id AND ur.thesis_round_id IS NULL);
    DROP TABLE IF EXISTS security;
    INSERT INTO app_seed_versions(version) VALUES('actor-rbac-v9');
END $migration$;

-- Upgrade: migrate_actor_constraints.sql
DO $migration$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,10);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='actor-rbac-v10-invariants') THEN RETURN; END IF;
    ALTER TABLE users ALTER COLUMN user_type SET NOT NULL;
    INSERT INTO app_seed_versions(version) VALUES('actor-rbac-v10-invariants');
END $migration$;

CREATE OR REPLACE FUNCTION enforce_account_roles() RETURNS trigger LANGUAGE plpgsql AS $body$
DECLARE account_id varchar(36); actor varchar(50);
BEGIN
    IF TG_TABLE_NAME='users' THEN account_id:=NEW.id;
    ELSIF TG_OP='DELETE' THEN account_id:=OLD.user_id;
    ELSE account_id:=NEW.user_id; END IF;
    SELECT user_type INTO actor FROM users WHERE id=account_id;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=account_id AND (
        (actor='STUDENT' AND r.role_code<>'STUDENT') OR (actor='ADMIN' AND r.role_code<>'ADMIN')
        OR (actor='LECTURER' AND r.role_code NOT IN ('LECTURER','FACULTY_STAFF','COMMITTEE'))
        OR (r.role_code='ADMIN' AND ur.thesis_round_id IS NOT NULL))) THEN
        RAISE EXCEPTION 'Role is incompatible with account type' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
        WHERE ur.user_id=account_id AND r.role_code=actor AND ur.thesis_round_id IS NULL) THEN
        RAISE EXCEPTION 'Account must retain its global base role' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $body$;
DO $triggers$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='ck_account_roles_on_membership') THEN
        CREATE CONSTRAINT TRIGGER ck_account_roles_on_membership AFTER INSERT OR UPDATE OR DELETE ON user_roles
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_account_roles();
        CREATE CONSTRAINT TRIGGER ck_account_roles_on_user AFTER INSERT OR UPDATE ON users
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_account_roles();
    END IF;
END $triggers$;

-- Core DATN menus and planning data. RBAC administration menus and grants stay intact.
DO $core$
DECLARE removed_codes text[];
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,11);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v11') THEN RETURN; END IF;
    CREATE TABLE IF NOT EXISTS academic_years (
        id varchar(36) PRIMARY KEY, code varchar(20) NOT NULL UNIQUE,
        start_year integer NOT NULL, end_year integer NOT NULL,
        CONSTRAINT ck_academic_year_span CHECK(end_year=start_year+1)
    );
    CREATE TABLE IF NOT EXISTS semesters (
        id varchar(36) PRIMARY KEY, academic_year_id varchar(36) NOT NULL REFERENCES academic_years(id),
        number integer NOT NULL CHECK(number IN (1,2,3)),
        UNIQUE(academic_year_id,number)
    );
    ALTER TABLE thesis_rounds ADD COLUMN IF NOT EXISTS semester_id varchar(36) REFERENCES semesters(id);
    ALTER TABLE thesis_rounds ADD COLUMN IF NOT EXISTS registration_opens_at timestamptz;
    ALTER TABLE thesis_rounds ADD COLUMN IF NOT EXISTS registration_closes_at timestamptz;
    ALTER TABLE thesis_rounds ADD COLUMN IF NOT EXISTS proposal_closes_at timestamptz;
    ALTER TABLE thesis_rounds ADD COLUMN IF NOT EXISTS workflow_definition_id varchar(255);
    CREATE TABLE IF NOT EXISTS round_lecturers (
        id varchar(36) PRIMARY KEY, round_id varchar(36) NOT NULL REFERENCES thesis_rounds(id),
        lecturer_id varchar(36) NOT NULL REFERENCES users(id),
        contact_email varchar(120), contact_phone varchar(30), orientation text,
        capacity integer NOT NULL DEFAULT 10 CHECK(capacity>0), active boolean NOT NULL DEFAULT true,
        UNIQUE(round_id,lecturer_id)
    );
    CREATE TABLE IF NOT EXISTS workflow_templates (
        id varchar(36) PRIMARY KEY, name varchar(150) NOT NULL,
        version integer NOT NULL DEFAULT 1, status varchar(20) NOT NULL DEFAULT 'DRAFT'
            CHECK(status IN ('DRAFT','PUBLISHED')),
        process_definition_id varchar(255), published_at timestamptz,
        created_at timestamptz NOT NULL DEFAULT now()
    );
    CREATE TABLE IF NOT EXISTS workflow_steps (
        id varchar(36) PRIMARY KEY, template_id varchar(36) NOT NULL REFERENCES workflow_templates(id) ON DELETE CASCADE,
        step_key varchar(60) NOT NULL, label varchar(150) NOT NULL,
        sort_order integer NOT NULL, kind varchar(20) NOT NULL CHECK(kind IN ('SUBMIT','REVIEW','TASK')),
        assignee_role varchar(30) NOT NULL CHECK(assignee_role IN ('STUDENT','LECTURER','FACULTY_STAFF','COMMITTEE')),
        due_days integer CHECK(due_days IS NULL OR due_days>=0),
        UNIQUE(template_id,step_key), UNIQUE(template_id,sort_order)
    );
    CREATE TABLE IF NOT EXISTS thesis_submissions (
        id varchar(36) PRIMARY KEY, thesis_id varchar(36) NOT NULL REFERENCES theses(id),
        step_key varchar(60) NOT NULL, submitted_by varchar(36) NOT NULL REFERENCES users(id),
        content text NOT NULL, attachment_url text,
        submitted_at timestamptz NOT NULL DEFAULT now()
    );
    CREATE TABLE IF NOT EXISTS thesis_feedback (
        id varchar(36) PRIMARY KEY, thesis_id varchar(36) NOT NULL REFERENCES theses(id),
        step_key varchar(60) NOT NULL, reviewer_id varchar(36) NOT NULL REFERENCES users(id),
        approved boolean NOT NULL, comment text NOT NULL,
        reviewed_at timestamptz NOT NULL DEFAULT now()
    );

    -- Keep the three existing RBAC screens exactly as they are.
    SELECT array_agg(code) INTO removed_codes FROM menus WHERE code NOT IN (
        'tong-quan','tim-giang-vien-de-tai','ds-giang-vien',
        'do-an-cua-toi','dang-ky-de-tai','tien-trinh-do-an','ho-so-da-nop',
        'ho-so-de-tai-giang-vien','ho-so-giang-vien','huong-dan-do-an','sinh-vien-huong-dan','ho-so-cho-duyet',
        'dot-do-an','nam-hoc','hoc-ky','ds-dot-do-an',
        'ho-so-tien-do','ds-ho-so-tien-do','duyet-de-cuong','trung-lap',
        'cau-hinh-quy-trinh','bao-cao-thong-ke','bao-cao-tien-do','theo-doi-tre-han',
        'nguoi-dung-phan-quyen','users','roles','menus'
    ) AND code NOT LIKE 'menu-%';
    IF EXISTS(SELECT 1 FROM menus WHERE code LIKE 'menu-%' AND parent_id IN
        (SELECT id FROM menus WHERE code=ANY(coalesce(removed_codes,ARRAY[]::text[])))) THEN
        RAISE EXCEPTION 'Custom menu below retired system menu requires manual move';
    END IF;
    UPDATE menus SET parent_id=(SELECT id FROM menus WHERE code='ho-so-tien-do')
      WHERE code='ds-ho-so-tien-do';
    INSERT INTO rbac_migration_quarantine(source_table,payload)
      SELECT 'menus',to_jsonb(m) FROM menus m WHERE m.code=ANY(coalesce(removed_codes,ARRAY[]::text[]));
    INSERT INTO rbac_migration_quarantine(source_table,payload)
      SELECT 'permissions',to_jsonb(p) FROM permissions p WHERE p.menu_code=ANY(coalesce(removed_codes,ARRAY[]::text[]));
    INSERT INTO rbac_migration_quarantine(source_table,payload)
      SELECT 'role_permissions',to_jsonb(rp) FROM role_permissions rp JOIN permissions p ON p.id=rp.permission_id
        WHERE p.menu_code=ANY(coalesce(removed_codes,ARRAY[]::text[]));
    UPDATE menus SET permission_code=NULL WHERE code=ANY(coalesce(removed_codes,ARRAY[]::text[]));
    DELETE FROM role_permissions rp USING permissions p WHERE rp.permission_id=p.id
      AND p.menu_code=ANY(coalesce(removed_codes,ARRAY[]::text[]));
    DELETE FROM role_allowed_permissions a USING permissions p WHERE a.permission_id=p.id
      AND p.menu_code=ANY(coalesce(removed_codes,ARRAY[]::text[]));
    DELETE FROM permissions WHERE menu_code=ANY(coalesce(removed_codes,ARRAY[]::text[]));
    DELETE FROM menus WHERE code=ANY(coalesce(removed_codes,ARRAY[]::text[]));

    UPDATE menus SET label='Giảng viên tham gia ĐATN' WHERE code='tim-giang-vien-de-tai';
    UPDATE menus SET label='Danh sách giảng viên' WHERE code='ds-giang-vien';
    UPDATE menus SET label='Hồ sơ đồ án của tôi' WHERE code='do-an-cua-toi';
    UPDATE menus SET label='Đăng ký đề tài & nộp đề cương' WHERE code='dang-ky-de-tai';
    UPDATE menus SET label='Tiến trình và hạn nộp' WHERE code='tien-trinh-do-an';
    UPDATE menus SET label='Các lần nộp & phản hồi' WHERE code='ho-so-da-nop';
    UPDATE menus SET label='Giảng viên hướng dẫn' WHERE code='ho-so-de-tai-giang-vien';
    UPDATE menus SET label='Công việc GVHD' WHERE code='ho-so-cho-duyet';
    UPDATE menus SET label='Duyệt hồ sơ Khoa' WHERE code='ho-so-tien-do';
    UPDATE menus SET label='Hàng chờ duyệt' WHERE code='duyet-de-cuong';
    UPDATE menus SET label='Theo dõi hồ sơ' WHERE code='ds-ho-so-tien-do';
    UPDATE menus SET label='Quản lý đợt ĐATN' WHERE code='dot-do-an';
    UPDATE menus SET label='Các đợt ĐATN' WHERE code='ds-dot-do-an';
    UPDATE menus SET label='Cấu hình quy trình' WHERE code='cau-hinh-quy-trinh';
    UPDATE menus SET label='Theo dõi tiến độ' WHERE code='bao-cao-thong-ke';
    UPDATE menus SET label='Tổng quan hồ sơ' WHERE code='bao-cao-tien-do';
    UPDATE menus SET label='Hồ sơ trễ hạn' WHERE code='theo-doi-tre-han';
    UPDATE menus SET parent_id=(SELECT id FROM menus WHERE code='bao-cao-thong-ke') WHERE code='theo-doi-tre-han';

    INSERT INTO permissions(id,code,name,module,menu_code,action,enabled,created_date,last_modified_date)
    VALUES(gen_random_uuid()::text,'VIEW_CAU_HINH_QUY_TRINH','Truy cập / Xem: Cấu hình quy trình',
        'MENU','cau-hinh-quy-trinh','VIEW',true,now(),now()) ON CONFLICT(code) DO NOTHING;
    UPDATE menus SET permission_code='VIEW_CAU_HINH_QUY_TRINH' WHERE code='cau-hinh-quy-trinh';
    INSERT INTO role_allowed_permissions(id,role_id,permission_id,created_date,last_modified_date)
      SELECT gen_random_uuid()::text,r.id,p.id,now(),now() FROM roles r CROSS JOIN permissions p
      WHERE r.role_code='ADMIN' AND p.code='VIEW_CAU_HINH_QUY_TRINH' ON CONFLICT(role_id,permission_id) DO NOTHING;
    INSERT INTO role_permissions(id,role_id,permission_id,created_date,last_modified_date)
      SELECT gen_random_uuid()::text,r.id,p.id,now(),now() FROM roles r CROSS JOIN permissions p
      WHERE r.role_code='ADMIN' AND p.code='VIEW_CAU_HINH_QUY_TRINH' ON CONFLICT(role_id,permission_id) DO NOTHING;
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v11');
END $core$;

-- Database invariants for the configured workflow and registration.
DO $core_constraints$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,12);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v12-invariants') THEN RETURN; END IF;
    CREATE UNIQUE INDEX IF NOT EXISTS uq_thesis_student_round ON theses(phase_id,student_id);
    CREATE INDEX IF NOT EXISTS ix_round_lecturers_round ON round_lecturers(round_id);
    CREATE INDEX IF NOT EXISTS ix_submissions_thesis_time ON thesis_submissions(thesis_id,submitted_at);
    CREATE INDEX IF NOT EXISTS ix_feedback_thesis_time ON thesis_feedback(thesis_id,reviewed_at);
    ALTER TABLE thesis_rounds ADD CONSTRAINT ck_round_windows
      CHECK(registration_opens_at IS NULL OR registration_closes_at IS NULL OR proposal_closes_at IS NULL
        OR (registration_opens_at<registration_closes_at AND registration_closes_at<=proposal_closes_at));
    ALTER TABLE theses ADD CONSTRAINT fk_thesis_round
      FOREIGN KEY(phase_id) REFERENCES thesis_rounds(id) NOT VALID;
    ALTER TABLE workflow_templates ADD CONSTRAINT ck_published_definition
      CHECK(status='DRAFT' OR process_definition_id IS NOT NULL);
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v12-invariants');
END $core_constraints$;

DO $core_windows$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,13);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v13-windows') THEN RETURN; END IF;
    CREATE TABLE IF NOT EXISTS round_step_windows (
        id varchar(36) PRIMARY KEY,
        round_id varchar(36) NOT NULL REFERENCES thesis_rounds(id),
        step_key varchar(60) NOT NULL,
        opens_at timestamptz NOT NULL,
        closes_at timestamptz NOT NULL,
        UNIQUE(round_id,step_key),
        CONSTRAINT ck_step_window CHECK(opens_at<closes_at)
    );
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v13-windows');
END $core_windows$;

-- Navigation audience is independent of API authority. Admin retains all backend authority,
-- but its navigation does not include student/lecturer workspaces.
DO $core_navigation$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,14);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v14-navigation') THEN RETURN; END IF;
    CREATE TABLE IF NOT EXISTS menu_actor_visibility (
        menu_code varchar(100) NOT NULL REFERENCES menus(code) ON DELETE CASCADE,
        user_type varchar(30) NOT NULL CHECK(user_type IN ('STUDENT','LECTURER','ADMIN')),
        PRIMARY KEY(menu_code,user_type)
    );
    INSERT INTO menu_actor_visibility(menu_code,user_type) VALUES
      ('tong-quan','STUDENT'),('tong-quan','LECTURER'),('tong-quan','ADMIN'),
      ('tim-giang-vien-de-tai','STUDENT'),('do-an-cua-toi','STUDENT'),
      ('ho-so-de-tai-giang-vien','LECTURER'),('huong-dan-do-an','LECTURER'),
      ('dot-do-an','LECTURER'),('dot-do-an','ADMIN'),
      ('ho-so-tien-do','LECTURER'),('ho-so-tien-do','ADMIN'),
      ('cau-hinh-quy-trinh','ADMIN'),
      ('bao-cao-thong-ke','LECTURER'),('bao-cao-thong-ke','ADMIN'),
      ('nguoi-dung-phan-quyen','ADMIN')
      ON CONFLICT DO NOTHING;
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v14-navigation');
END $core_navigation$;

-- A thesis group contains its registering student and at most one partner.
DO $core_groups$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,15);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v15-groups') THEN RETURN; END IF;
    ALTER TABLE members ADD COLUMN IF NOT EXISTS thesis_round_id varchar(36);
    UPDATE members m SET thesis_round_id=t.phase_id FROM theses t WHERE t.id=m.thesis_id AND m.thesis_round_id IS NULL;
    INSERT INTO members(thesis_id,user_id,thesis_round_id)
      SELECT t.id,t.student_id,t.phase_id FROM theses t
      ON CONFLICT(thesis_id,user_id) DO NOTHING;
    ALTER TABLE members ALTER COLUMN thesis_round_id SET NOT NULL;
    ALTER TABLE members ADD CONSTRAINT fk_group_thesis FOREIGN KEY(thesis_id) REFERENCES theses(id);
    ALTER TABLE members ADD CONSTRAINT fk_group_student FOREIGN KEY(user_id) REFERENCES users(id);
    ALTER TABLE members ADD CONSTRAINT fk_group_round FOREIGN KEY(thesis_round_id) REFERENCES thesis_rounds(id);
    CREATE UNIQUE INDEX uq_group_student_round ON members(thesis_round_id,user_id);
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v15-groups');
END $core_groups$;

CREATE OR REPLACE FUNCTION enforce_thesis_group() RETURNS trigger LANGUAGE plpgsql AS $group$
DECLARE actual_round varchar(36); actor_type varchar(50); existing_count integer;
BEGIN
    SELECT phase_id INTO actual_round FROM theses WHERE id=NEW.thesis_id FOR UPDATE;
    SELECT user_type INTO actor_type FROM users WHERE id=NEW.user_id;
    IF actual_round IS DISTINCT FROM NEW.thesis_round_id OR actor_type IS DISTINCT FROM 'STUDENT' THEN
        RAISE EXCEPTION 'Thesis group requires a student in the same round' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        SELECT count(*) INTO existing_count FROM members WHERE thesis_id=NEW.thesis_id;
    ELSE
        SELECT count(*) INTO existing_count FROM members
          WHERE thesis_id=NEW.thesis_id AND user_id<>OLD.user_id;
    END IF;
    IF existing_count>=2 THEN
        RAISE EXCEPTION 'Thesis group cannot exceed two students' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $group$;
DO $group_trigger$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='ck_thesis_group_members') THEN
        CREATE TRIGGER ck_thesis_group_members BEFORE INSERT OR UPDATE ON members
          FOR EACH ROW EXECUTE FUNCTION enforce_thesis_group();
    END IF;
END $group_trigger$;

-- Contact details belong to the account; a lecturer's orientation belongs to the round.
DO $lecturer_profile$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,16);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v16-lecturer-profile') THEN RETURN; END IF;
    ALTER TABLE users ADD COLUMN IF NOT EXISTS phone varchar(30);
    UPDATE users u SET phone=source.phone FROM (
        SELECT lecturer_id,max(contact_phone) AS phone
        FROM round_lecturers
        WHERE contact_phone IS NOT NULL AND btrim(contact_phone)<>''
        GROUP BY lecturer_id HAVING count(DISTINCT contact_phone)=1
    ) source WHERE u.id=source.lecturer_id AND (u.phone IS NULL OR btrim(u.phone)='');
    ALTER TABLE round_lecturers DROP COLUMN IF EXISTS contact_email;
    ALTER TABLE round_lecturers DROP COLUMN IF EXISTS contact_phone;
    ALTER TABLE round_lecturers DROP COLUMN IF EXISTS capacity;
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v16-lecturer-profile');
END $lecturer_profile$;

-- Proposal timing belongs to the workflow step window, alongside later submissions.
DO $proposal_step_window$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,17);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v17-proposal-window') THEN RETURN; END IF;
    INSERT INTO round_step_windows(id,round_id,step_key,opens_at,closes_at)
      SELECT gen_random_uuid()::text,r.id,'submitProposal',r.registration_opens_at,r.proposal_closes_at
      FROM thesis_rounds r
      WHERE r.registration_opens_at IS NOT NULL AND r.proposal_closes_at IS NOT NULL
        AND r.registration_opens_at<r.proposal_closes_at
      ON CONFLICT(round_id,step_key) DO NOTHING;
    ALTER TABLE thesis_rounds DROP CONSTRAINT IF EXISTS ck_round_windows;
    ALTER TABLE thesis_rounds ADD CONSTRAINT ck_round_windows
      CHECK(registration_opens_at IS NULL OR registration_closes_at IS NULL
        OR registration_opens_at<registration_closes_at);
    ALTER TABLE thesis_rounds DROP COLUMN IF EXISTS proposal_closes_at;
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v17-proposal-window');
END $proposal_step_window$;


-- One graduation round per semester, including closed rounds.
DO $one_round_per_semester$
BEGIN
    PERFORM pg_advisory_xact_lock(20261005,18);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v18-one-round-per-semester') THEN RETURN; END IF;
    IF EXISTS(SELECT 1 FROM thesis_rounds WHERE semester_id IS NOT NULL GROUP BY semester_id HAVING count(*)>1) THEN
        RAISE EXCEPTION 'Duplicate graduation rounds exist for a semester; resolve them before applying the one-round constraint';
    END IF;
    CREATE UNIQUE INDEX IF NOT EXISTS uq_thesis_rounds_semester ON thesis_rounds(semester_id);
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v18-one-round-per-semester');
END $one_round_per_semester$;

-- Merge navigation only: preserve academic_years, semesters and all referencing IDs.
DO $academic_calendar$
BEGIN
    PERFORM pg_advisory_xact_lock(20261006,19);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v19-academic-calendar') THEN RETURN; END IF;
    IF NOT EXISTS(SELECT 1 FROM menus WHERE code='nam-hoc') THEN
        RAISE EXCEPTION 'Academic year menu must exist before merging the calendar';
    END IF;
    -- Preserve existing semester viewers when consolidating both screens.
    INSERT INTO role_allowed_permissions(id,role_id,permission_id,created_date,last_modified_date)
      SELECT gen_random_uuid()::text,a.role_id,target.id,now(),now()
      FROM role_allowed_permissions a JOIN permissions old ON old.id=a.permission_id
      CROSS JOIN permissions target WHERE old.code='VIEW_HOC_KY' AND target.code='VIEW_NAM_HOC'
      ON CONFLICT(role_id,permission_id) DO NOTHING;
    INSERT INTO role_permissions(id,role_id,permission_id,created_date,last_modified_date)
      SELECT gen_random_uuid()::text,g.role_id,target.id,now(),now()
      FROM role_permissions g JOIN permissions old ON old.id=g.permission_id
      CROSS JOIN permissions target WHERE old.code='VIEW_HOC_KY' AND target.code='VIEW_NAM_HOC'
      ON CONFLICT(role_id,permission_id) DO NOTHING;
    UPDATE menus SET label='Năm học & Học kỳ',path='/rounds/academic-years',last_modified_date=now() WHERE code='nam-hoc';
    UPDATE menus SET parent_id=(SELECT id FROM menus WHERE code='nam-hoc'),last_modified_date=now()
      WHERE parent_id=(SELECT id FROM menus WHERE code='hoc-ky');
    UPDATE menus SET active=false,last_modified_date=now() WHERE code='hoc-ky';
    UPDATE permissions SET enabled=false,last_modified_date=now() WHERE code='VIEW_HOC_KY';
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v19-academic-calendar');
END $academic_calendar$;

-- The current DATN baseline is a new draft. Earlier published definitions and
-- running instances remain untouched for their original semesters.
DO $workflow_v20$
BEGIN
    PERFORM pg_advisory_xact_lock(20261007,20);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v20-proposal-first') THEN RETURN; END IF;
    ALTER TABLE workflow_steps ADD COLUMN IF NOT EXISTS next_key varchar(60);
    ALTER TABLE workflow_steps ADD COLUMN IF NOT EXISTS reject_key varchar(60);
    CREATE TABLE IF NOT EXISTS defense_schedules (
        id varchar(36) PRIMARY KEY,
        thesis_id varchar(36) NOT NULL UNIQUE REFERENCES theses(id),
        defense_at timestamptz NOT NULL,
        room varchar(120) NOT NULL,
        council_name varchar(150) NOT NULL,
        notes text,
        published_by varchar(36) NOT NULL REFERENCES users(id),
        published_at timestamptz NOT NULL DEFAULT now()
    );
    CREATE TABLE IF NOT EXISTS workflow_mail_outbox (
        id varchar(36) PRIMARY KEY,
        round_id varchar(36) REFERENCES thesis_rounds(id),
        step_key varchar(60),
        event_key varchar(60) NOT NULL,
        recipient_email varchar(255) NOT NULL,
        subject varchar(255) NOT NULL,
        body text NOT NULL,
        status varchar(20) NOT NULL DEFAULT 'PENDING',
        attempts integer NOT NULL DEFAULT 0,
        last_error text,
        created_at timestamptz NOT NULL DEFAULT now(),
        sent_at timestamptz,
        UNIQUE(round_id,step_key,event_key,recipient_email)
    );
    INSERT INTO workflow_templates(id,name,version,status)
      VALUES('core-template-v2','Quy trình ĐATN từ đề cương đến lịch bảo vệ',2,'DRAFT')
      ON CONFLICT(id) DO NOTHING;
    INSERT INTO workflow_steps(id,template_id,step_key,label,sort_order,kind,assignee_role,due_days,next_key,reject_key)
      SELECT gen_random_uuid()::text,'core-template-v2',v.step_key,v.label,v.sort_order,v.kind,v.assignee_role,v.due_days,v.next_key,v.reject_key
      FROM (VALUES
        ('submitProposal','Sinh viên nộp đề cương',1,'SUBMIT','STUDENT',NULL::integer,NULL::varchar,NULL::varchar),
        ('reviewProposal','Khoa kiểm tra và phản hồi đề cương',2,'REVIEW','FACULTY_STAFF',5,NULL::varchar,'submitProposal'),
        ('reviseProposal','Sinh viên hoàn thiện đề cương theo phản hồi',3,'SUBMIT','STUDENT',NULL::integer,NULL::varchar,NULL::varchar),
        ('advisorSignature','Giảng viên xác nhận đề cương',4,'REVIEW','LECTURER',5,NULL::varchar,'reviseProposal'),
        ('submitSignedProposal','Sinh viên nộp đề cương đã xác nhận',5,'SUBMIT','STUDENT',NULL::integer,NULL::varchar,NULL::varchar),
        ('facultyFinalApproval','Khoa duyệt đề cương chính thức',6,'REVIEW','FACULTY_STAFF',5,NULL::varchar,'reviseProposal'),
        ('registerThesis','Sinh viên xác nhận đăng ký ĐATN',7,'TASK','STUDENT',NULL::integer,NULL::varchar,NULL::varchar),
        ('confirmGuidance','Giảng viên xác nhận hướng dẫn ĐATN',8,'REVIEW','LECTURER',5,NULL::varchar,'registerThesis'),
        ('midtermReport','Sinh viên nộp báo cáo giữa kỳ',9,'SUBMIT','STUDENT',NULL::integer,NULL::varchar,NULL::varchar),
        ('reviewMidterm','Giảng viên phản hồi báo cáo giữa kỳ',10,'REVIEW','LECTURER',5,NULL::varchar,'midtermReport'),
        ('finalReport','Sinh viên nộp báo cáo đồ án cho GVHD',11,'SUBMIT','STUDENT',NULL::integer,NULL::varchar,NULL::varchar),
        ('advisorFinalReview','GVHD nhận xét báo cáo đồ án',12,'REVIEW','LECTURER',5,NULL::varchar,'finalReport'),
        ('submitCouncil','Sinh viên nộp báo cáo cho Hội đồng',13,'SUBMIT','STUDENT',NULL::integer,NULL::varchar,NULL::varchar),
        ('councilAccept','Hội đồng tiếp nhận hồ sơ bảo vệ',14,'REVIEW','COMMITTEE',5,NULL::varchar,'submitCouncil'),
        ('scheduleDefense','Khoa công bố lịch bảo vệ',15,'TASK','FACULTY_STAFF',5,NULL::varchar,NULL::varchar)
      ) v(step_key,label,sort_order,kind,assignee_role,due_days,next_key,reject_key)
      ON CONFLICT(template_id,step_key) DO NOTHING;
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v20-proposal-first');
END $workflow_v20$;

DO $mail_v21$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,21);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v21-mail-settings') THEN RETURN; END IF;
    CREATE TABLE IF NOT EXISTS round_mail_settings (
        round_id varchar(36) PRIMARY KEY REFERENCES thesis_rounds(id),
        remind_before_hours integer NOT NULL DEFAULT 48 CHECK(remind_before_hours BETWEEN 0 AND 720),
        remind_after_hours integer NOT NULL DEFAULT 24 CHECK(remind_after_hours BETWEEN 0 AND 720),
        updated_at timestamptz NOT NULL DEFAULT now()
    );
    CREATE TABLE IF NOT EXISTS round_mail_campaigns (
        id varchar(36) PRIMARY KEY,
        round_id varchar(36) NOT NULL REFERENCES thesis_rounds(id),
        subject varchar(255) NOT NULL,
        message text NOT NULL,
        scheduled_at timestamptz NOT NULL,
        status varchar(20) NOT NULL DEFAULT 'SCHEDULED',
        recipient_count integer NOT NULL DEFAULT 0,
        created_at timestamptz NOT NULL DEFAULT now(),
        queued_at timestamptz
    );
    CREATE INDEX IF NOT EXISTS ix_workflow_mail_pending ON workflow_mail_outbox(status,created_at);
    CREATE INDEX IF NOT EXISTS ix_round_mail_campaign_due ON round_mail_campaigns(status,scheduled_at);
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v21-mail-settings');
END $mail_v21$;

-- Remove only obsolete starter records that have never been used by a round or case.
DO $starter_cleanup_v22$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,22);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v22-starter-cleanup') THEN RETURN; END IF;
    DELETE FROM workflow_steps WHERE template_id='core-template-v1'
      AND EXISTS(SELECT 1 FROM workflow_templates WHERE id='core-template-v1' AND status='DRAFT')
      AND NOT EXISTS(SELECT 1 FROM thesis_rounds r JOIN workflow_templates wt ON wt.process_definition_id=r.workflow_definition_id WHERE wt.id='core-template-v1');
    DELETE FROM workflow_templates WHERE id='core-template-v1' AND status='DRAFT'
      AND NOT EXISTS(SELECT 1 FROM workflow_steps WHERE template_id='core-template-v1');
    DELETE FROM semesters WHERE academic_year_id='core-year-2026'
      AND NOT EXISTS(SELECT 1 FROM thesis_rounds WHERE semester_id=semesters.id);
    DELETE FROM academic_years WHERE id='core-year-2026'
      AND NOT EXISTS(SELECT 1 FROM semesters WHERE academic_year_id='core-year-2026');
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v22-starter-cleanup');
END $starter_cleanup_v22$;

DO $mail_retry_v23$
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,23);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='core-workflow-v23-mail-retry') THEN RETURN; END IF;
    ALTER TABLE workflow_mail_outbox ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz NOT NULL DEFAULT now();
    INSERT INTO app_seed_versions(version) VALUES('core-workflow-v23-mail-retry');
END $mail_retry_v23$;

-- Local bootstrap: admin / admin123 when no APP_BOOTSTRAP_PASSWORD is supplied.
-- Run after account migrations. Never reset existing credentials or role assignments.
DO $bootstrap_admin_v24$
DECLARE
    admin_id varchar(36);
    admin_role_id varchar(36);
BEGIN
    PERFORM pg_advisory_xact_lock(20261004,24);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='bootstrap-admin-v24') THEN RETURN; END IF;
    IF NOT EXISTS(SELECT 1 FROM users WHERE username='admin') THEN
        SELECT id INTO STRICT admin_role_id FROM roles WHERE role_code='ADMIN';
        admin_id := gen_random_uuid()::text;
        INSERT INTO users(id,username,email,full_name,password_hash,user_type,status,created_date,last_modified_date)
        VALUES(admin_id,'admin','admin@graduation.local','Quản trị viên Nguyễn Văn An',
            COALESCE(NULLIF(current_setting('app.bootstrap.password_hash',true),''),
                '$2a$10$ctVd2m8G2Nc2RRpYTyl6zOtBbasSqZsDt9WF1ysgY3z005.lDPSl.'),
            'ADMIN','ACTIVE',now(),now());
        INSERT INTO user_roles(id,user_id,role_id,thesis_round_id,created_date,last_modified_date)
        VALUES(gen_random_uuid()::text,admin_id,admin_role_id,NULL,now(),now());
    END IF;
    INSERT INTO app_seed_versions(version) VALUES('bootstrap-admin-v24');
END $bootstrap_admin_v24$;

-- Separate submission windows and email operations from the round directory.
DO $round_schedule_v25$
BEGIN
    PERFORM pg_advisory_xact_lock(20261007,26);
    IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='round-schedule-menu-v25') THEN RETURN; END IF;
    INSERT INTO menus(id,code,label,icon,path,parent_id,sort_order,active,created_date,last_modified_date)
    VALUES(gen_random_uuid()::text,'moc-nop-thong-bao','Mốc nộp & thông báo','ScheduleOutlined','/rounds/schedule',
        (SELECT id FROM menus WHERE code='dot-do-an'),74,true,now(),now()) ON CONFLICT(code) DO NOTHING;
    INSERT INTO permissions(id,code,name,module,menu_code,action,enabled,created_date,last_modified_date)
    VALUES(gen_random_uuid()::text,'VIEW_MOC_NOP_THONG_BAO','Truy cập / Xem: Mốc nộp & thông báo',
        'MENU','moc-nop-thong-bao','VIEW',true,now(),now()) ON CONFLICT(code) DO NOTHING;
    UPDATE menus SET permission_code='VIEW_MOC_NOP_THONG_BAO' WHERE code='moc-nop-thong-bao';
    -- These settings use the existing ADMIN-only API endpoints.
    INSERT INTO role_allowed_permissions(id,role_id,permission_id,created_date,last_modified_date)
    SELECT gen_random_uuid()::text,r.id,p.id,now(),now() FROM roles r CROSS JOIN permissions p
    WHERE r.role_code='ADMIN' AND p.code='VIEW_MOC_NOP_THONG_BAO' ON CONFLICT(role_id,permission_id) DO NOTHING;
    INSERT INTO role_permissions(id,role_id,permission_id,created_date,last_modified_date)
    SELECT gen_random_uuid()::text,r.id,p.id,now(),now() FROM roles r CROSS JOIN permissions p
    WHERE r.role_code='ADMIN' AND p.code='VIEW_MOC_NOP_THONG_BAO' ON CONFLICT(role_id,permission_id) DO NOTHING;
    UPDATE roles SET permissions_version=permissions_version+1 WHERE role_code='ADMIN';
    INSERT INTO app_seed_versions(version) VALUES('round-schedule-menu-v25');
END $round_schedule_v25$;

-- User permission overrides and demo accounts.
-- Forward-only auth migration. Loaded after the existing versioned baseline.
DO $auth_v1$
BEGIN
  PERFORM pg_advisory_xact_lock(20261010,1);
  IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='auth-screen-bundles-v1') THEN RETURN; END IF;
  ALTER TABLE users ADD COLUMN IF NOT EXISTS permissions_version bigint NOT NULL DEFAULT 0;
  CREATE TABLE IF NOT EXISTS user_permission_denies (
    id varchar(36) PRIMARY KEY, user_id varchar(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission_id varchar(36) NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    thesis_round_id varchar(36) REFERENCES thesis_rounds(id) ON DELETE CASCADE,
    created_by varchar(50),last_modified_by varchar(50),created_date timestamp,last_modified_date timestamp
  );
  CREATE UNIQUE INDEX IF NOT EXISTS ux_user_deny_global ON user_permission_denies(user_id,permission_id) WHERE thesis_round_id IS NULL;
  CREATE UNIQUE INDEX IF NOT EXISTS ux_user_deny_round ON user_permission_denies(user_id,permission_id,thesis_round_id) WHERE thesis_round_id IS NOT NULL;
  INSERT INTO menus(id,code,label,path,icon,sort_order,active,parent_id,created_date,last_modified_date)
  SELECT gen_random_uuid()::text,v.code,v.label,v.path,'SafetyCertificateOutlined',v.ord,true,
    CASE WHEN v.code='user-permissions' THEN (SELECT id FROM menus WHERE code='nguoi-dung-phan-quyen') ELSE NULL END,now(),now()
  FROM (VALUES ('user-permissions','Phân quyền người dùng','/admin/user-permissions',143),
    ('khoa-bo-mon','Khoa / Bộ môn','/catalogs/departments',160),
    ('nhat-ky-thao-tac','Nhật ký thao tác','/settings/audit',170),
    ('tac-vu-quy-trinh','Tác vụ quy trình','/tasks',110)) v(code,label,path,ord)
  ON CONFLICT(code) DO NOTHING;
  INSERT INTO permissions(id,code,name,module,menu_code,action,enabled,created_date,last_modified_date)
  SELECT gen_random_uuid()::text,'VIEW_'||upper(replace(m.code,'-','_')),'Truy cập: '||m.label,'MENU',m.code,'VIEW',true,now(),now()
  FROM menus m WHERE m.code IN ('user-permissions','khoa-bo-mon','nhat-ky-thao-tac','tac-vu-quy-trinh') ON CONFLICT(code) DO NOTHING;
  UPDATE menus SET permission_code='VIEW_'||upper(replace(code,'-','_')) WHERE code IN ('user-permissions','khoa-bo-mon','nhat-ky-thao-tac','tac-vu-quy-trinh');
  INSERT INTO permissions(id,code,name,module,menu_code,action,enabled,created_date,last_modified_date)
  SELECT gen_random_uuid()::text,v.code,v.name,'MENU_ACTION',v.menu,v.action,true,now(),now()
  FROM (VALUES
    ('USER_PERMISSIONS_UPDATE','Sửa giới hạn quyền người dùng','user-permissions','UPDATE'),
    ('DEPARTMENTS_CREATE','Thêm khoa / bộ môn','khoa-bo-mon','CREATE'),
    ('DEPARTMENTS_UPDATE','Sửa khoa / bộ môn','khoa-bo-mon','UPDATE'),
    ('DEPARTMENTS_DELETE','Xóa khoa / bộ môn','khoa-bo-mon','DELETE'),
    ('CALENDAR_CREATE','Thêm năm học / học kỳ','nam-hoc','CREATE'),
    ('CALENDAR_UPDATE','Sửa năm học / học kỳ','nam-hoc','UPDATE'),
    ('ROUNDS_CREATE','Tạo đợt / thêm giảng viên','ds-dot-do-an','CREATE'),
    ('ROUNDS_UPDATE','Sửa đợt / gán quy trình','ds-dot-do-an','UPDATE'),
    ('ROUNDS_DELETE','Bỏ giảng viên khỏi đợt','ds-dot-do-an','DELETE'),
    ('WORKFLOW_CREATE','Tạo bản nháp','cau-hinh-quy-trinh','CREATE'),
    ('WORKFLOW_UPDATE','Sửa bản nháp','cau-hinh-quy-trinh','UPDATE'),
    ('WORKFLOW_APPROVE','Công bố quy trình','cau-hinh-quy-trinh','APPROVE'),
    ('SCHEDULE_CREATE','Tạo thông báo','moc-nop-thong-bao','CREATE'),
    ('SCHEDULE_UPDATE','Sửa lịch / mốc nộp','moc-nop-thong-bao','UPDATE'),
    ('THESIS_CREATE','Đăng ký đề tài','dang-ky-de-tai','CREATE'),
    ('THESIS_UPDATE','Sửa hồ sơ','ds-ho-so-tien-do','UPDATE'),
    ('THESIS_DELETE','Xóa hồ sơ','ds-ho-so-tien-do','DELETE'),
    ('TASKS_UPDATE','Nhận việc / nộp hồ sơ','tac-vu-quy-trinh','UPDATE'),
    ('TASKS_APPROVE','Duyệt tác vụ','tac-vu-quy-trinh','APPROVE'),
    ('AUDIT_CREATE','Ghi chú nhật ký','nhat-ky-thao-tac','CREATE')) v(code,name,menu,action)
  JOIN menus m ON m.code=v.menu ON CONFLICT(code) DO UPDATE SET enabled=true,action=excluded.action,menu_code=excluded.menu_code;
  -- Expand each role's existing screen limits to include supported actions.
  INSERT INTO role_allowed_permissions(id,role_id,permission_id,created_date,last_modified_date)
  SELECT gen_random_uuid()::text,r.id,p.id,now(),now() FROM roles r CROSS JOIN permissions p
  WHERE p.enabled AND (r.role_code='ADMIN'
    OR (p.menu_code='tac-vu-quy-trinh')
    OR (r.role_code='FACULTY_STAFF' AND p.menu_code IN ('khoa-bo-mon','nam-hoc','ds-dot-do-an','cau-hinh-quy-trinh','moc-nop-thong-bao','ds-ho-so-tien-do','nhat-ky-thao-tac'))
    OR EXISTS(SELECT 1 FROM role_allowed_permissions a JOIN permissions v ON v.id=a.permission_id
      WHERE a.role_id=r.id AND v.action='VIEW' AND v.menu_code=p.menu_code))
  ON CONFLICT(role_id,permission_id) DO NOTHING;
  -- Existing selected screens become full bundles, including demo business screens for faculty.
  INSERT INTO role_permissions(id,role_id,permission_id,created_date,last_modified_date)
  SELECT gen_random_uuid()::text,a.role_id,a.permission_id,now(),now() FROM role_allowed_permissions a
  JOIN permissions p ON p.id=a.permission_id JOIN roles r ON r.id=a.role_id
  WHERE p.enabled AND (r.role_code='ADMIN' OR p.menu_code='tac-vu-quy-trinh'
    OR (r.role_code='FACULTY_STAFF' AND p.menu_code IN ('khoa-bo-mon','nam-hoc','ds-dot-do-an','cau-hinh-quy-trinh','moc-nop-thong-bao','ds-ho-so-tien-do','nhat-ky-thao-tac'))
    OR EXISTS(SELECT 1 FROM role_permissions g JOIN permissions v ON v.id=g.permission_id
      WHERE g.role_id=a.role_id AND v.action='VIEW' AND v.menu_code=p.menu_code))
  ON CONFLICT(role_id,permission_id) DO NOTHING;
  UPDATE roles SET permissions_version=permissions_version+1;
  INSERT INTO app_seed_versions(version) VALUES('auth-screen-bundles-v1');
END $auth_v1$;

-- Local demo accounts, loaded with the single initialization script.
-- Password for these accounts: admin123.
-- Versioned: restarting never restores restrictions changed during a demo.
DO $auth_demo$
BEGIN
  PERFORM pg_advisory_xact_lock(20261010,2);
  IF EXISTS(SELECT 1 FROM app_seed_versions WHERE version='auth-demo-v1') THEN RETURN; END IF;
  INSERT INTO users(id,username,email,full_name,password_hash,user_type,status,created_date,last_modified_date)
  SELECT gen_random_uuid()::text,v.username,v.username||'@graduation.local',v.name,
    '$2a$10$ctVd2m8G2Nc2RRpYTyl6zOtBbasSqZsDt9WF1ysgY3z005.lDPSl.','LECTURER','ACTIVE',now(),now()
  FROM (VALUES ('demo.full','Demo Khoa - toàn quyền'),('demo.view1','Demo Khoa - chỉ xem 1'),
    ('demo.view2','Demo Khoa - chỉ xem 2')) v(username,name) ON CONFLICT(username) DO NOTHING;
  INSERT INTO user_roles(id,user_id,role_id,created_date,last_modified_date)
  SELECT gen_random_uuid()::text,u.id,r.id,now(),now() FROM users u CROSS JOIN roles r
  WHERE u.username IN ('demo.full','demo.view1','demo.view2') AND r.role_code IN ('LECTURER','FACULTY_STAFF')
    AND NOT EXISTS(SELECT 1 FROM user_roles ur WHERE ur.user_id=u.id AND ur.role_id=r.id AND ur.thesis_round_id IS NULL);
  INSERT INTO user_permission_denies(id,user_id,permission_id,created_date,last_modified_date)
  SELECT gen_random_uuid()::text,u.id,p.id,now(),now() FROM users u CROSS JOIN permissions p
  WHERE u.username IN ('demo.view1','demo.view2') AND p.enabled AND p.action<>'VIEW'
  ON CONFLICT DO NOTHING;
  INSERT INTO departments(id,dept_code,dept_name,created_date,last_modified_date)
  VALUES('auth-demo-department','DEMO-AUTH','Khoa demo phân quyền',now(),now()) ON CONFLICT DO NOTHING;
  INSERT INTO app_seed_versions(version) VALUES('auth-demo-v1');
END $auth_demo$;
