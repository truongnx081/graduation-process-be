-- Local demo. Executed automatically by DataInitializer when app.seed-auth-demo=true (default).
-- Run inside a transaction after seed_rbac_and_menus.sql and migrate_user_permissions.sql. Password for these accounts: admin123.
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
