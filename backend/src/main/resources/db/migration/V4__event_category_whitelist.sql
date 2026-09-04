-- ---------------------------------------------------------------------------
-- 活动分类固定为白名单
--
-- 修复：category 此前是无约束的 VARCHAR(50)，主办方在创建表单里可以自由填写。
-- 于是同一个意思散成好几个孤岛（music / Music / 音乐 / 工作坊）：活动能保存、
-- 详情页也显示正常，但发现页的分类筛选用的是精确匹配，这些活动永远搜不出来。
--
-- 三层一起封：前端下拉框、后端 @ValidEventCategory + EventCategory.normalise，
-- 以及这里的 CHECK 约束 —— 兜住绕过 API 的直写（seeder、SQL 脚本、psql）。
--
-- 顺序不能反：先把存量归一化并收编无法归类的值，再落约束，否则 ALTER 会被
-- 已有的脏数据顶回来。白名单需与 domain/EventCategory.java 和
-- frontend/src/types.ts 的 CATEGORIES 保持一致，三处要一起改。
-- ---------------------------------------------------------------------------

-- 1. 大小写与首尾空格归一化：'Music' / ' music ' 本来就该是 'music'。
UPDATE events
   SET category = lower(trim(category))
 WHERE category <> lower(trim(category));

-- 2. 仍然落在白名单外的（自定义分类、其他语言写法）统一收进 'other'，
--    保住 NOT NULL 的同时让它们至少能被「其他」这个筛选项检索到。
UPDATE events
   SET category = 'other'
 WHERE category NOT IN ('music', 'tech', 'sports', 'art', 'food', 'business', 'community', 'other');

-- 3. 最后一道防线。
ALTER TABLE events
    ADD CONSTRAINT events_category_check
    CHECK (category IN ('music', 'tech', 'sports', 'art', 'food', 'business', 'community', 'other'));
