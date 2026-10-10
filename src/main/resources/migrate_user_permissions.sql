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
