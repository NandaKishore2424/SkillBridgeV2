-- The demo dataset: two colleges, sixteen students, and enough of everything
-- else that no screen in the application is empty.
--
-- Run it through scripts/db/seed-demo.sh, which supplies :demo_password (by
-- \getenv, so it is never on a command line) and waits for the skill-gap
-- reports. Running it directly needs the psql variable set, e.g.:
--
--     psql -v demo_password='...' -f scripts/db/demo-seed.sql
--
-- WHAT IT TOUCHES, AND WHAT IT WILL NOT
--
-- It replaces every row of application data: colleges and everything that
-- cascades from them, plus the audit trail and the messaging plumbing, which
-- would otherwise describe rows that no longer exist. It is a reset, not a
-- top-up, so running it twice gives the same database both times.
--
-- It never touches:
--   * industry_job_descriptions -- the 1,500 job descriptions and their
--     embeddings, restored from the 2026-09-19 backup. The ETL's source CSV is
--     gone, so these are irreplaceable and there is no code here that writes to
--     that table.
--   * roles                     -- a closed enumeration, backed by a CHECK.
--   * flyway_schema_history     -- Flyway's, not ours.
--
-- WHY THE SKILLS CATALOGUE GROWS
--
-- `skills` is the vocabulary a student picks from, and it arrived with 44 rows
-- aimed at software engineering. The job corpus is 1,500 analyst postings:
-- measured on 2026-09-19, 577 of them ask for SQL, 356 for Excel, 212 for
-- Tableau -- and not one of those three was in the catalogue, so no student
-- could record the single most-demanded skill in the market this product
-- analyses. Section 1 adds the analytics vocabulary, matched to the names
-- skillbridge-ai-service/skill_extraction.py recognises, so that a student's
-- skills and a job's requirements can actually meet.
--
-- Section 1 also lists the software-engineering skills this file uses, which
-- the 44 rows already cover. That looks redundant and is not: those 44 rows
-- live only in the database restored from the backup. `skills` is deliberately
-- absent from db/schema/reference-data.sql and from the Flyway migrations, so a
-- database built from the migrations alone has none of them -- and an inner
-- join against a name that is not there drops the row in silence. Naming every
-- skill it uses is what makes this file work on a database built either way.
-- Everything here is ON CONFLICT DO NOTHING; no existing row is altered.
--
-- WHY PGCRYPTO IS BORROWED AND GIVEN BACK
--
-- Every demo account shares one password, which is never stored here: it
-- arrives as :demo_password and is bcrypt-hashed by pgcrypto's crypt(), which
-- produces the $2a$ form Spring's BCryptPasswordEncoder reads. seed-demo.sh
-- drops the extension afterwards if it installed it, so the local database
-- still fingerprints identically to one Flyway builds from the migrations.

\set ON_ERROR_STOP on

BEGIN;

-- ---------------------------------------------------------------------------
-- 0. Clear the previous run
-- ---------------------------------------------------------------------------

-- Plumbing first. Both are transient by design: outbox_events is a queue that
-- has been drained, and dead_letter_events records failures against ids that
-- are about to stop existing. processed_events is the AI service's
-- deduplication ledger, keyed by event id -- stale rows there would silently
-- suppress the analyses this script is about to ask for.
DELETE FROM outbox_events;
DELETE FROM dead_letter_events;
DELETE FROM processed_events;

-- The audit trail refers to actors and resources by id. Keeping it across a
-- reset leaves a log about users who no longer exist, which is worse than an
-- empty one: it reads as real history and every id in it is a dangling
-- reference.
DELETE FROM audit_log;

-- enrollment_requests.reviewed_by is the one foreign key to users that does not
-- cascade or null out, so a request reviewed by a user who is about to be
-- deleted would refuse the delete below. It cascades from batches anyway; doing
-- it first only removes the dependence on the order Postgres happens to unwind
-- the cascade in.
DELETE FROM enrollment_requests;

-- Everything else hangs off colleges by ON DELETE CASCADE: users, students,
-- trainers, college_admins, batches, the syllabus, enrolments, progress,
-- feedback, placements, companies, skill-gap reports, refresh tokens.
DELETE FROM colleges;

-- Except a SYSTEM_ADMIN, who belongs to no college and so cascades from
-- nothing.
DELETE FROM users WHERE college_id IS NULL;

-- ---------------------------------------------------------------------------
-- 1. The vocabulary
-- ---------------------------------------------------------------------------
--
-- Every skill name section 4 assigns, whether or not the live catalogue already
-- had it. Categories match the 44 original rows so a restored database sees no
-- change at all. See the header for why the second group is here.

INSERT INTO skills (name, category) VALUES
    -- Added by this file: what the job corpus asks for.
    ('SQL',                'Database'),
    ('Snowflake',          'Database'),
    ('BigQuery',           'Database'),
    ('Excel',              'Data & Analytics'),
    ('Tableau',            'Data & Analytics'),
    ('Power BI',           'Data & Analytics'),
    ('Pandas',             'Data & Analytics'),
    ('NumPy',              'Data & Analytics'),
    ('Spark',              'Data & Analytics'),
    ('Airflow',            'Data & Analytics'),
    ('Kafka',              'Data & Analytics'),
    ('ETL',                'Data & Analytics'),
    ('Statistics',         'Data & Analytics'),
    ('Data Visualization', 'Data & Analytics'),
    ('scikit-learn',       'AI/ML'),
    ('NLP',                'AI/ML'),
    ('R',                  'Programming'),

    -- Already among the 44 in the restored database, and absent from a database
    -- built from the migrations. Listed so this file works on both.
    ('Python',             'Programming'),
    ('Java',               'Programming'),
    ('JavaScript',         'Programming'),
    ('Machine Learning',   'AI/ML'),
    ('Deep Learning',      'AI/ML'),
    ('TensorFlow',         'AI/ML'),
    ('Spring Boot',        'Framework'),
    ('React',              'Framework'),
    ('Node.js',            'Framework'),
    ('PostgreSQL',         'Database'),
    ('MySQL',              'Database'),
    ('MongoDB',            'Database'),
    ('Redis',              'Database'),
    ('Docker',             'DevOps'),
    ('Kubernetes',         'DevOps'),
    ('AWS',                'Cloud'),
    ('Git',                'Tools'),
    ('Linux',              'Tools')
ON CONFLICT (name) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Colleges
-- ---------------------------------------------------------------------------
--
-- Two of them, because one tenant proves nothing. Every isolation claim in
-- docs/SECURITY.md is a claim that Hillview cannot see Northgate, and that is
-- only demonstrable with a Northgate to try.

INSERT INTO colleges (name, code, email, phone, address, status) VALUES
    ('Hillview Institute of Technology', 'HIT',
     'registrar@hillview.test', '04428120011',
     '12 Rajiv Gandhi Salai, Taramani, Chennai 600113', 'ACTIVE'),
    ('Northgate College of Engineering', 'NGC',
     'office@northgate.test', '02026150022',
     '7 Senapati Bapat Road, Shivajinagar, Pune 411016', 'ACTIVE');

-- ---------------------------------------------------------------------------
-- 3. People
-- ---------------------------------------------------------------------------
--
-- One list, read four times: into users, into user_roles, and then into
-- whichever of college_admins / trainers / students the role calls for. Listing
-- each person once is the only way the four stay consistent.
--
-- `pace` is how far through their batch's syllabus the student is, as a
-- percentage. Section 9 turns it into topic-by-topic progress, so the spread on
-- a trainer's screen is a property of this table and not of a random number.

CREATE TEMP TABLE demo_person (
    college_code  text,
    email         text,
    role          text,
    full_name     text,
    phone         text,
    -- students
    roll_number   text,
    degree        text,
    branch        text,
    year          int,
    pace          int,
    -- trainers
    department    text,
    specialization text,
    years_experience int
) ON COMMIT DROP;

INSERT INTO demo_person
    (college_code, email, role, full_name, phone,
     roll_number, degree, branch, year, pace,
     department, specialization, years_experience)
VALUES
    -- The platform operator. No college: they administer all of them.
    (NULL,  'admin@skillbridge.test',    'SYSTEM_ADMIN',  'Platform Administrator', '08041000000',
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),

    -- Hillview -----------------------------------------------------------
    ('HIT', 'priya@hillview.test',       'COLLEGE_ADMIN', 'Priya Raghavan',   '09840011221',
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    ('HIT', 'arun@hillview.test',        'TRAINER',       'Arun Menon',       '09840011222',
            NULL, NULL, NULL, NULL, NULL,
            'Computer Science', 'Data engineering, SQL and distributed processing', 9),
    ('HIT', 'kavitha@hillview.test',     'TRAINER',       'Kavitha Iyer',     '09840011223',
            NULL, NULL, NULL, NULL, NULL,
            'Computer Science', 'Business intelligence, Tableau and applied statistics', 6),

    ('HIT', 'aditi@hillview.test',       'STUDENT', 'Aditi Sharma',    '09840012301',
            'HIT2301', 'B.Tech', 'Computer Science', 4, 85, NULL, NULL, NULL),
    ('HIT', 'rohan@hillview.test',       'STUDENT', 'Rohan Verma',     '09840012302',
            'HIT2302', 'B.Tech', 'Computer Science', 4, 70, NULL, NULL, NULL),
    ('HIT', 'neha@hillview.test',        'STUDENT', 'Neha Pillai',     '09840012303',
            'HIT2303', 'B.Tech', 'Information Technology', 4, 60, NULL, NULL, NULL),
    ('HIT', 'karthik@hillview.test',     'STUDENT', 'Karthik Reddy',   '09840012304',
            'HIT2304', 'B.Tech', 'Computer Science', 3, 45, NULL, NULL, NULL),
    ('HIT', 'ishita@hillview.test',      'STUDENT', 'Ishita Bose',     '09840012305',
            'HIT2305', 'B.Tech', 'Information Technology', 4, 90, NULL, NULL, NULL),
    ('HIT', 'manav@hillview.test',       'STUDENT', 'Manav Joshi',     '09840012306',
            'HIT2306', 'B.Tech', 'Electronics', 3, 20, NULL, NULL, NULL),
    ('HIT', 'sneha@hillview.test',       'STUDENT', 'Sneha Nair',      '09840012307',
            'HIT2307', 'B.Tech', 'Computer Science', 3, 55, NULL, NULL, NULL),
    ('HIT', 'vikram@hillview.test',      'STUDENT', 'Vikram Singh',    '09840012308',
            'HIT2308', 'B.Tech', 'Information Technology', 3, 35, NULL, NULL, NULL),
    ('HIT', 'ananya@hillview.test',      'STUDENT', 'Ananya Gupta',    '09840012309',
            'HIT2309', 'M.Tech', 'Data Science', 2, 80, NULL, NULL, NULL),
    ('HIT', 'farhan@hillview.test',      'STUDENT', 'Farhan Qureshi',  '09840012310',
            'HIT2310', 'B.Tech', 'Computer Science', 4, 65, NULL, NULL, NULL),
    ('HIT', 'divya@hillview.test',       'STUDENT', 'Divya Menon',     '09840012311',
            'HIT2311', 'M.Tech', 'Artificial Intelligence', 2, 75, NULL, NULL, NULL),
    -- Deliberately has no skills. The analysis stores a SKIPPED report for such
    -- a student, so their screen says "add your skills" rather than sitting on
    -- a spinner for ever, and that path needs somebody to demonstrate it.
    ('HIT', 'tarun@hillview.test',       'STUDENT', 'Tarun Bhat',      '09840012312',
            'HIT2312', 'B.Tech', 'Mechanical', 3, 10, NULL, NULL, NULL),

    -- Northgate ----------------------------------------------------------
    ('NGC', 'sanjay@northgate.test',     'COLLEGE_ADMIN', 'Sanjay Deshmukh', '09820022331',
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    ('NGC', 'meera@northgate.test',      'TRAINER',       'Meera Kulkarni',  '09820022332',
            NULL, NULL, NULL, NULL, NULL,
            'Statistics', 'Applied statistics and machine learning', 7),

    ('NGC', 'aisha@northgate.test',      'STUDENT', 'Aisha Khan',       '09820022401',
            'NGC2401', 'B.E.', 'Computer Engineering', 4, 70, NULL, NULL, NULL),
    ('NGC', 'rahul@northgate.test',      'STUDENT', 'Rahul Deshpande',  '09820022402',
            'NGC2402', 'B.E.', 'Computer Engineering', 3, 50, NULL, NULL, NULL),
    ('NGC', 'pooja@northgate.test',      'STUDENT', 'Pooja Shetty',     '09820022403',
            'NGC2403', 'B.E.', 'Information Technology', 3, 30, NULL, NULL, NULL),
    ('NGC', 'nikhil@northgate.test',     'STUDENT', 'Nikhil Rao',       '09820022404',
            'NGC2404', 'B.E.', 'Computer Engineering', 4, 60, NULL, NULL, NULL);

-- One hash, computed once and shared. Twenty-two separate bcrypt calls at cost
-- 10 would add about a second to a script whose whole point is being quick to
-- re-run, and the accounts share the password anyway.
CREATE TEMP TABLE demo_secret ON COMMIT DROP AS
    SELECT crypt(:'demo_password', gen_salt('bf', 10)) AS password_hash;

INSERT INTO users (college_id, email, password_hash, is_active,
                   must_change_password, account_status, profile_completed,
                   invitation_sent_at, first_login_at)
SELECT c.id, p.email, s.password_hash, true,
       -- Already onboarded: a demo that begins with twenty forced password
       -- changes is a demo of the password-change screen.
       false, 'ACTIVE', true,
       now() - interval '30 days', now() - interval '29 days'
FROM demo_person p
CROSS JOIN demo_secret s
LEFT JOIN colleges c ON c.code = p.college_code;

-- One student is left as invited-but-never-signed-in, which is what a bulk CSV
-- import leaves behind when somebody does not act on their email. It is also
-- the only state in which "resend invitation" works: InvitationIssuer refuses
-- anything that is not PENDING_SETUP, with a 409.
--
-- Without this row that button answers 409 for every student in the database,
-- and the invitation-email flow cannot be shown at all. Found by walking
-- the app against seeded data on 2026-09-20.
UPDATE users SET account_status = 'PENDING_SETUP',
                 must_change_password = true,
                 first_login_at = NULL,
                 profile_completed = false
 WHERE email = 'sneha@hillview.test';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM demo_person p
JOIN users u ON u.email = p.email
JOIN roles r ON r.name = p.role;

INSERT INTO college_admins (user_id, college_id, full_name, phone)
SELECT u.id, u.college_id, p.full_name, p.phone
FROM demo_person p
JOIN users u ON u.email = p.email
WHERE p.role = 'COLLEGE_ADMIN';

INSERT INTO trainers (user_id, college_id, full_name, phone, department,
                      specialization, years_of_experience)
SELECT u.id, u.college_id, p.full_name, p.phone, p.department,
       p.specialization, p.years_experience
FROM demo_person p
JOIN users u ON u.email = p.email
WHERE p.role = 'TRAINER';

INSERT INTO students (user_id, college_id, roll_number, full_name, phone,
                      degree, branch, year, bio, github_url)
SELECT u.id, u.college_id, p.roll_number, p.full_name, p.phone,
       p.degree, p.branch, p.year,
       'Final-project student at ' || c.name || '.',
       'https://github.com/' || split_part(p.email, '@', 1) || '-demo'
FROM demo_person p
JOIN users u ON u.email = p.email
JOIN colleges c ON c.id = u.college_id
WHERE p.role = 'STUDENT';

-- ---------------------------------------------------------------------------
-- 4. What each student can do
-- ---------------------------------------------------------------------------
--
-- This is the table the whole AI feature reads, and before this script it was
-- empty in every local database -- which is why no skill-gap report had ever
-- been produced outside a test.
--
-- The profiles are deliberately uneven. An analyst who is already close to the
-- market, a backend student who is a long way from it, and a student with
-- nothing recorded are three different reports, and one of each is worth more
-- for a demo than sixteen near-identical ones.

-- Held in a temp table rather than joined straight from a VALUES list, so
-- section 13 can say which line was wrong. A misspelt skill or roll number in
-- an inner join is not an error: the row simply does not appear, and the first
-- anyone knows is a student whose report is missing a skill they were given.
CREATE TEMP TABLE demo_student_skill (roll_number text, skill_name text, proficiency int) ON COMMIT DROP;

INSERT INTO demo_student_skill (roll_number, skill_name, proficiency)
VALUES
    -- Close to the market already: the report should find few gaps.
    ('HIT2301', 'SQL', 4), ('HIT2301', 'Excel', 4), ('HIT2301', 'Tableau', 3),
    ('HIT2301', 'Python', 3), ('HIT2301', 'Statistics', 3),
    ('HIT2301', 'Data Visualization', 3),

    -- Python and modelling, no BI tooling: gaps should be sql, excel, tableau.
    ('HIT2302', 'Python', 4), ('HIT2302', 'Pandas', 3), ('HIT2302', 'NumPy', 3),
    ('HIT2302', 'Machine Learning', 3), ('HIT2302', 'scikit-learn', 2),

    ('HIT2303', 'Excel', 4), ('HIT2303', 'Power BI', 4), ('HIT2303', 'SQL', 3),
    ('HIT2303', 'Data Visualization', 3),

    -- A backend engineer measured against an analyst corpus: the widest gap in
    -- the dataset, and the clearest illustration that the report is computed
    -- from the market rather than from the syllabus.
    ('HIT2304', 'Java', 4), ('HIT2304', 'Spring Boot', 3),
    ('HIT2304', 'PostgreSQL', 3), ('HIT2304', 'Docker', 2), ('HIT2304', 'Git', 3),

    ('HIT2305', 'SQL', 4), ('HIT2305', 'Spark', 3), ('HIT2305', 'Airflow', 2),
    ('HIT2305', 'Python', 3), ('HIT2305', 'ETL', 3), ('HIT2305', 'Kafka', 2),

    -- Two skills, both beginner: a long missing list, honestly earned.
    ('HIT2306', 'Python', 2), ('HIT2306', 'SQL', 1),

    ('HIT2307', 'AWS', 3), ('HIT2307', 'Docker', 3), ('HIT2307', 'Kubernetes', 2),
    ('HIT2307', 'Linux', 3), ('HIT2307', 'Git', 3),

    ('HIT2308', 'JavaScript', 4), ('HIT2308', 'React', 3), ('HIT2308', 'Node.js', 3),
    ('HIT2308', 'MongoDB', 2), ('HIT2308', 'Git', 3),

    ('HIT2309', 'SQL', 4), ('HIT2309', 'Python', 4), ('HIT2309', 'Tableau', 3),
    ('HIT2309', 'Statistics', 4), ('HIT2309', 'Machine Learning', 2),
    ('HIT2309', 'Pandas', 3),

    ('HIT2310', 'PostgreSQL', 4), ('HIT2310', 'MySQL', 3), ('HIT2310', 'SQL', 4),
    ('HIT2310', 'Redis', 2), ('HIT2310', 'Python', 2),

    ('HIT2311', 'Machine Learning', 4), ('HIT2311', 'Deep Learning', 3),
    ('HIT2311', 'TensorFlow', 3), ('HIT2311', 'Python', 4), ('HIT2311', 'NLP', 2),

    -- HIT2312 (Tarun Bhat) is absent on purpose. See section 3.

    ('NGC2401', 'SQL', 3), ('NGC2401', 'Python', 3), ('NGC2401', 'Excel', 3),
    ('NGC2402', 'Python', 4), ('NGC2402', 'Pandas', 3), ('NGC2402', 'Machine Learning', 3),
    ('NGC2403', 'Excel', 4), ('NGC2403', 'Power BI', 3), ('NGC2403', 'SQL', 2),
    ('NGC2404', 'Java', 3), ('NGC2404', 'Spring Boot', 3), ('NGC2404', 'SQL', 2);

INSERT INTO student_skills (student_id, skill_id, proficiency_level)
SELECT st.id, sk.id, v.proficiency
FROM demo_student_skill v
JOIN students st ON st.roll_number = v.roll_number
JOIN skills sk ON sk.name = v.skill_name;

-- ---------------------------------------------------------------------------
-- 5. Companies
-- ---------------------------------------------------------------------------

INSERT INTO companies (college_id, name, domain, hiring_type)
SELECT c.id, v.name, v.domain, v.hiring_type
FROM (VALUES
    ('HIT', 'Trilytics Analytics',  'trilytics.test',  'BOTH'),
    ('HIT', 'Northwind Software',   'northwind.test',  'FULL_TIME'),
    ('HIT', 'Vayu Cloud Services',  'vayucloud.test',  'INTERNSHIP'),
    ('NGC', 'Deccan Data Labs',     'deccandata.test', 'BOTH')
) AS v(code, name, domain, hiring_type)
JOIN colleges c ON c.code = v.code;

-- ---------------------------------------------------------------------------
-- 6. Batches
-- ---------------------------------------------------------------------------
--
-- Four statuses are represented on purpose: ACTIVE has progress to show, OPEN
-- has enrolment requests waiting for a decision, COMPLETED has history, and the
-- screens behave differently for each.

INSERT INTO batches (college_id, name, description, status, start_date, end_date, capacity)
SELECT c.id, v.name, v.description, v.status,
       current_date + v.start_offset, current_date + v.end_offset, v.capacity
FROM (VALUES
    ('HIT', 'Data Analytics 2026',
     'Twelve weeks of SQL, Python and business intelligence, ending in a capstone dashboard.',
     'ACTIVE',    -49,  35, 30),
    ('HIT', 'Backend Engineering 2026',
     'Java and Spring Boot, from dependency injection to a deployed, observable service.',
     'ACTIVE',    -35,  49, 25),
    ('HIT', 'Cloud & DevOps 2026',
     'Containers, pipelines and AWS fundamentals. Enrolment is open.',
     'OPEN',       21, 126, 20),
    ('HIT', 'Data Analytics 2025',
     'The previous cohort. Kept for the placement record.',
     'COMPLETED', -400, -280, 30),
    ('NGC', 'Applied Data Science 2026',
     'Statistics first, then supervised learning and putting a model into service.',
     'ACTIVE',    -28,  56, 24)
) AS v(code, name, description, status, start_offset, end_offset, capacity)
JOIN colleges c ON c.code = v.code;

INSERT INTO batch_trainers (batch_id, trainer_id, role_description)
SELECT b.id, t.id, v.role_description
FROM (VALUES
    ('HIT', 'Data Analytics 2026',       'arun@hillview.test',    'Lead trainer, SQL and data engineering'),
    ('HIT', 'Data Analytics 2026',       'kavitha@hillview.test', 'Business intelligence and statistics'),
    ('HIT', 'Backend Engineering 2026',  'arun@hillview.test',    'Lead trainer'),
    ('HIT', 'Cloud & DevOps 2026',       'arun@hillview.test',    'Lead trainer'),
    ('HIT', 'Data Analytics 2025',       'kavitha@hillview.test', 'Lead trainer'),
    ('NGC', 'Applied Data Science 2026', 'meera@northgate.test',  'Lead trainer')
) AS v(code, batch_name, trainer_email, role_description)
JOIN colleges c ON c.code = v.code
JOIN batches b ON b.college_id = c.id AND b.name = v.batch_name
JOIN users u ON u.email = v.trainer_email
JOIN trainers t ON t.user_id = u.id;

INSERT INTO batch_companies (batch_id, company_id, hiring_count, requirements)
SELECT b.id, co.id, v.hiring_count, v.requirements
FROM (VALUES
    ('HIT', 'Data Analytics 2026',       'Trilytics Analytics', 6, 'SQL, one BI tool, and a portfolio dashboard.'),
    ('HIT', 'Data Analytics 2026',       'Vayu Cloud Services', 3, 'Interns only. Python and SQL.'),
    ('HIT', 'Backend Engineering 2026',  'Northwind Software',  4, 'Java, Spring Boot, and a deployed service.'),
    ('HIT', 'Data Analytics 2025',       'Trilytics Analytics', 5, 'Closed cohort.'),
    ('NGC', 'Applied Data Science 2026', 'Deccan Data Labs',    4, 'Statistics, Python, and one production model.')
) AS v(code, batch_name, company_name, hiring_count, requirements)
JOIN colleges c ON c.code = v.code
JOIN batches b ON b.college_id = c.id AND b.name = v.batch_name
JOIN companies co ON co.college_id = c.id AND co.name = v.company_name;

-- ---------------------------------------------------------------------------
-- 7. Syllabus
-- ---------------------------------------------------------------------------
--
-- Three levels, because the trainer's curriculum screen renders all three and a
-- flat list would not exercise it. The names are the demo's script: a reviewer
-- reading "Window Functions" under "SQL for Analysts" can tell at a glance what
-- the batch teaches.

INSERT INTO syllabus_modules (batch_id, college_id, name, description, display_order, start_date, end_date)
SELECT b.id, b.college_id, v.name, v.description, v.display_order,
       b.start_date + (v.display_order - 1) * 28,
       b.start_date + v.display_order * 28 - 1
FROM (VALUES
    ('Data Analytics 2026',       1, 'Foundations of Data Analysis', 'Where the data lives and how to ask it questions.'),
    ('Data Analytics 2026',       2, 'Python for Data',              'Pandas, and turning a dataframe into a picture.'),
    ('Data Analytics 2026',       3, 'Business Intelligence',        'Dashboards people act on, and the statistics behind them.'),
    ('Backend Engineering 2026',  1, 'Java and Spring Boot',         'Dependency injection through to a working HTTP API.'),
    ('Backend Engineering 2026',  2, 'Production Concerns',          'What changes once somebody depends on the service.'),
    ('Cloud & DevOps 2026',       1, 'Containers and Pipelines',     'Docker, CI, and getting a build to a server.'),
    ('Data Analytics 2025',       1, 'Data Analysis Fundamentals',   'The 2025 cohort''s first and only module on record.'),
    ('Applied Data Science 2026', 1, 'Applied Statistics',           'Probability and inference, with data in front of you.'),
    ('Applied Data Science 2026', 2, 'Machine Learning',             'Supervised learning, and what happens after it ships.')
) AS v(batch_name, display_order, name, description)
JOIN batches b ON b.name = v.batch_name;

INSERT INTO syllabus_submodules (module_id, name, description, display_order, week_number, start_date, end_date)
SELECT m.id, v.name, v.description, v.display_order,
       (m.display_order - 1) * 4 + v.display_order * 2,
       m.start_date + (v.display_order - 1) * 14,
       m.start_date + v.display_order * 14 - 1
FROM (VALUES
    ('Data Analytics 2026',       'Foundations of Data Analysis', 1, 'SQL for Analysts',        'Reading a schema you did not design.'),
    ('Data Analytics 2026',       'Foundations of Data Analysis', 2, 'Spreadsheets at Scale',   'Excel, past the point most people stop.'),
    ('Data Analytics 2026',       'Python for Data',              1, 'Pandas Essentials',       'Loading, reshaping and joining.'),
    ('Data Analytics 2026',       'Python for Data',              2, 'Visualisation',           'Charts that answer the question asked.'),
    ('Data Analytics 2026',       'Business Intelligence',        1, 'Tableau',                 'From a connection to a published dashboard.'),
    ('Data Analytics 2026',       'Business Intelligence',        2, 'Statistics for Decisions','Knowing when a difference is real.'),
    ('Backend Engineering 2026',  'Java and Spring Boot',         1, 'Core Spring',             'Beans, configuration and data access.'),
    ('Backend Engineering 2026',  'Java and Spring Boot',         2, 'Building APIs',           'Controllers, validation and errors.'),
    ('Backend Engineering 2026',  'Production Concerns',          1, 'Persistence',             'Transactions and the connection pool.'),
    ('Backend Engineering 2026',  'Production Concerns',          2, 'Delivery',                'Images, pipelines and knowing it is alive.'),
    ('Cloud & DevOps 2026',       'Containers and Pipelines',     1, 'Docker',                  'Images, layers and a small one.'),
    ('Cloud & DevOps 2026',       'Containers and Pipelines',     2, 'Continuous Integration',  'A pipeline that says no.'),
    ('Data Analytics 2025',       'Data Analysis Fundamentals',   1, 'SQL Basics',              'SELECT, WHERE, JOIN.'),
    ('Data Analytics 2025',       'Data Analysis Fundamentals',   2, 'Reporting',               'Charts and the sentence underneath them.'),
    ('Applied Data Science 2026', 'Applied Statistics',           1, 'Probability',             'Distributions and sampling.'),
    ('Applied Data Science 2026', 'Applied Statistics',           2, 'Inference',               'Intervals, regression and diagnostics.'),
    ('Applied Data Science 2026', 'Machine Learning',             1, 'Supervised Learning',     'Linear models through to ensembles.'),
    ('Applied Data Science 2026', 'Machine Learning',             2, 'Putting Models to Work',  'Pipelines, serving and drift.')
) AS v(batch_name, module_name, display_order, name, description)
JOIN batches b ON b.name = v.batch_name
JOIN syllabus_modules m ON m.batch_id = b.id AND m.name = v.module_name;

-- Temp table for the same reason as the skills: a topic whose submodule name is
-- misspelt is silently not created, and a syllabus quietly missing three topics
-- is not something anyone notices by reading it.
CREATE TEMP TABLE demo_topic (submodule_name text, display_order int, name text) ON COMMIT DROP;

INSERT INTO demo_topic (submodule_name, display_order, name)
VALUES
    ('SQL for Analysts',         1, 'Joins and Aggregation'),
    ('SQL for Analysts',         2, 'Window Functions'),
    ('SQL for Analysts',         3, 'Reading a Query Plan'),
    ('Spreadsheets at Scale',    1, 'Pivot Tables'),
    ('Spreadsheets at Scale',    2, 'Lookup Functions'),
    ('Spreadsheets at Scale',    3, 'Cleaning Messy Data'),
    ('Pandas Essentials',        1, 'Series and DataFrames'),
    ('Pandas Essentials',        2, 'Grouping and Merging'),
    ('Pandas Essentials',        3, 'Time Series'),
    ('Visualisation',            1, 'Choosing a Chart'),
    ('Visualisation',            2, 'Distributions'),
    ('Visualisation',            3, 'Small Multiples'),
    ('Tableau',                  1, 'Connecting a Data Source'),
    ('Tableau',                  2, 'Calculated Fields'),
    ('Tableau',                  3, 'Publishing a Dashboard'),
    ('Statistics for Decisions', 1, 'Descriptive Statistics'),
    ('Statistics for Decisions', 2, 'Hypothesis Testing'),
    ('Statistics for Decisions', 3, 'Designing an A/B Test'),
    ('Core Spring',              1, 'Dependency Injection'),
    ('Core Spring',              2, 'Configuration and Profiles'),
    ('Core Spring',              3, 'Spring Data JPA'),
    ('Building APIs',            1, 'REST Controllers'),
    ('Building APIs',            2, 'Validation and Error Handling'),
    ('Building APIs',            3, 'Versioning an API'),
    ('Persistence',              1, 'Transaction Boundaries'),
    ('Persistence',              2, 'Connection Pooling'),
    ('Persistence',              3, 'Finding a Slow Query'),
    ('Delivery',                 1, 'Building an Image'),
    ('Delivery',                 2, 'A Pipeline That Says No'),
    ('Delivery',                 3, 'Logs, Metrics and Traces'),
    ('Docker',                   1, 'Images and Layers'),
    ('Docker',                   2, 'Compose for Local Work'),
    ('Continuous Integration',   1, 'A First Pipeline'),
    ('Continuous Integration',   2, 'Caching a Build'),
    ('SQL Basics',               1, 'SELECT and WHERE'),
    ('SQL Basics',               2, 'Joining Two Tables'),
    ('SQL Basics',               3, 'Grouping and Counting'),
    ('Reporting',                1, 'Charts That Answer'),
    ('Reporting',                2, 'Telling the Story'),
    ('Probability',              1, 'Distributions'),
    ('Probability',              2, 'Bayes in Practice'),
    ('Probability',              3, 'Sampling'),
    ('Inference',                1, 'Confidence Intervals'),
    ('Inference',                2, 'Linear Regression'),
    ('Inference',                3, 'Model Diagnostics'),
    ('Supervised Learning',      1, 'Linear Models'),
    ('Supervised Learning',      2, 'Trees and Ensembles'),
    ('Supervised Learning',      3, 'Evaluation Metrics'),
    ('Putting Models to Work',   1, 'Feature Pipelines'),
    ('Putting Models to Work',   2, 'Serving a Model'),
    ('Putting Models to Work',   3, 'Monitoring Drift');

INSERT INTO syllabus_topics (submodule_id, name, display_order, session_number)
SELECT sm.id, v.name, v.display_order, v.display_order
FROM demo_topic v
JOIN syllabus_submodules sm ON sm.name = v.submodule_name;

-- Which topics the trainer has taught, batch-wide. This is the syllabus's own
-- flag, not any one student's progress: it moves with the calendar, so a batch
-- that started seven weeks ago has covered its first weeks' topics.
UPDATE syllabus_topics t
SET is_completed = true,
    completed_at = sm.start_date + t.display_order * 2
FROM syllabus_submodules sm
JOIN syllabus_modules m ON m.id = sm.module_id
JOIN batches b ON b.id = m.batch_id
WHERE t.submodule_id = sm.id
  AND sm.start_date + t.display_order * 2 < current_date;

-- ---------------------------------------------------------------------------
-- 8. Enrolments, and three decisions waiting to be made
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE demo_enrollment (batch_name text, roll_number text, status text, enrolled_by text) ON COMMIT DROP;

INSERT INTO demo_enrollment (batch_name, roll_number, status, enrolled_by)
VALUES
    ('Data Analytics 2026',       'HIT2301', 'ACTIVE',    'priya@hillview.test'),
    ('Data Analytics 2026',       'HIT2302', 'ACTIVE',    'priya@hillview.test'),
    ('Data Analytics 2026',       'HIT2303', 'ACTIVE',    'priya@hillview.test'),
    ('Data Analytics 2026',       'HIT2305', 'ACTIVE',    'priya@hillview.test'),
    ('Data Analytics 2026',       'HIT2306', 'ACTIVE',    'priya@hillview.test'),
    ('Data Analytics 2026',       'HIT2309', 'ACTIVE',    'priya@hillview.test'),
    ('Data Analytics 2026',       'HIT2310', 'ACTIVE',    'priya@hillview.test'),
    ('Data Analytics 2026',       'HIT2312', 'ACTIVE',    'priya@hillview.test'),
    ('Backend Engineering 2026',  'HIT2304', 'ACTIVE',    'priya@hillview.test'),
    ('Backend Engineering 2026',  'HIT2307', 'ACTIVE',    'priya@hillview.test'),
    ('Backend Engineering 2026',  'HIT2308', 'ACTIVE',    'priya@hillview.test'),
    ('Backend Engineering 2026',  'HIT2311', 'ACTIVE',    'priya@hillview.test'),
    ('Data Analytics 2025',       'HIT2301', 'COMPLETED', 'priya@hillview.test'),
    ('Data Analytics 2025',       'HIT2303', 'COMPLETED', 'priya@hillview.test'),
    ('Applied Data Science 2026', 'NGC2401', 'ACTIVE',    'sanjay@northgate.test'),
    ('Applied Data Science 2026', 'NGC2402', 'ACTIVE',    'sanjay@northgate.test'),
    ('Applied Data Science 2026', 'NGC2403', 'ACTIVE',    'sanjay@northgate.test'),
    ('Applied Data Science 2026', 'NGC2404', 'ACTIVE',    'sanjay@northgate.test');

INSERT INTO enrollments (batch_id, student_id, college_id, status, enrolled_at, enrolled_by, completed_at)
SELECT b.id, st.id, b.college_id, v.status,
       b.start_date - interval '3 days',
       (SELECT u.id FROM users u WHERE u.email = v.enrolled_by),
       CASE WHEN v.status = 'COMPLETED' THEN b.end_date + interval '1 day' END
FROM demo_enrollment v
JOIN batches b ON b.name = v.batch_name
JOIN students st ON st.roll_number = v.roll_number;

-- Left PENDING on purpose: approving one of these, on camera, is the shortest
-- path to showing that a decision is attributed to the admin who made it and
-- that the reason they type is stored and shown back.
INSERT INTO enrollment_requests (batch_id, student_id, trainer_id, college_id,
                                 request_type, status, source, reason, created_at)
SELECT b.id, st.id, t.id, b.college_id, 'ADD', 'PENDING', v.source, v.reason,
       now() - (v.days_ago || ' days')::interval
FROM (VALUES
    ('Cloud & DevOps 2026', 'HIT2306', 'arun@hillview.test', 'TRAINER_REQUEST',
     'Manav is behind on Data Analytics; the DevOps basics would give him a second route.', 4),
    ('Cloud & DevOps 2026', 'HIT2308', 'arun@hillview.test', 'TRAINER_REQUEST',
     'Vikram already works with Docker on his own projects.', 2),
    ('Cloud & DevOps 2026', 'HIT2310', 'arun@hillview.test', 'TRAINER_REQUEST',
     'Farhan asked to add cloud fundamentals before placement season.', 1)
) AS v(batch_name, roll_number, trainer_email, source, reason, days_ago)
JOIN batches b ON b.name = v.batch_name
JOIN students st ON st.roll_number = v.roll_number
JOIN users u ON u.email = v.trainer_email
JOIN trainers t ON t.user_id = u.id;

-- ---------------------------------------------------------------------------
-- 9. Progress
-- ---------------------------------------------------------------------------
--
-- Derived from demo_person.pace rather than written out: sixteen students
-- against fifty-one topics is far too many rows to list, and a rule is easier
-- to check than a table of numbers.
--
-- Within a batch the topics are numbered in teaching order. A student with pace
-- p has finished the first p% of them, is working on the next one, and has not
-- started the rest. A student under 40% leaves every third finished topic at
-- NEEDS_IMPROVEMENT, which is what puts them on the at-risk report. A batch
-- that has already finished is finished for everyone in it, whatever their
-- pace: a COMPLETED batch with a student still mid-topic is a state the
-- application would never produce, and seeding one would send whoever reads
-- these screens looking for the bug that caused it.
--
-- Scores follow pace, so the student who is behind is not also carrying the
-- highest marks in the batch.

INSERT INTO topic_progress (student_id, syllabus_topic_id, status, updated_by,
                            batch_id, college_id, score, started_at, completed_at, updated_at)
SELECT
    ordered.student_id,
    ordered.topic_id,
    ordered.status,
    ordered.trainer_id,
    -- Overwritten by trg_progress_denorm, which derives both from the topic.
    -- Supplied because the columns are NOT NULL and the trigger runs after the
    -- row is built.
    0, 0,
    CASE ordered.status
        WHEN 'COMPLETED'         THEN 55 + round(ordered.pace * 0.35) + (ordered.position * 7) % 10
        WHEN 'NEEDS_IMPROVEMENT' THEN 30 + (ordered.position * 5) % 25
    END,
    CASE WHEN ordered.status <> 'PENDING' THEN now() - (ordered.position || ' days')::interval END,
    CASE WHEN ordered.status IN ('COMPLETED', 'NEEDS_IMPROVEMENT')
         THEN now() - (ordered.position || ' days')::interval END,
    now() - (ordered.position || ' days')::interval
FROM (
    SELECT
        numbered.student_id,
        numbered.topic_id,
        numbered.position,
        numbered.pace,
        numbered.trainer_id,
        CASE
            WHEN numbered.batch_status = 'COMPLETED' THEN 'COMPLETED'
            WHEN numbered.position <= floor(numbered.total * numbered.pace / 100.0)
                THEN CASE WHEN numbered.pace < 40 AND numbered.position % 3 = 0
                          THEN 'NEEDS_IMPROVEMENT' ELSE 'COMPLETED' END
            WHEN numbered.position = floor(numbered.total * numbered.pace / 100.0) + 1
                THEN 'IN_PROGRESS'
            ELSE 'PENDING'
        END AS status
    FROM (
        SELECT
            e.student_id,
            t.id AS topic_id,
            p.pace,
            b.status AS batch_status,
            (SELECT bt.trainer_id FROM batch_trainers bt
              WHERE bt.batch_id = e.batch_id ORDER BY bt.trainer_id LIMIT 1) AS trainer_id,
            row_number() OVER (PARTITION BY e.student_id, e.batch_id
                               ORDER BY m.display_order, sm.display_order, t.display_order) AS position,
            count(*)    OVER (PARTITION BY e.student_id, e.batch_id) AS total
        FROM enrollments e
        JOIN batches b ON b.id = e.batch_id
        JOIN students st ON st.id = e.student_id
        JOIN demo_person p ON p.roll_number = st.roll_number
        JOIN syllabus_modules m ON m.batch_id = e.batch_id
        JOIN syllabus_submodules sm ON sm.module_id = m.id
        JOIN syllabus_topics t ON t.submodule_id = sm.id
    ) numbered
) ordered;

-- The denormalised rollup the dashboards read. Recomputed here with the same
-- arithmetic as ProgressService.recomputeSummary -- COMPLETED counts 1,
-- IN_PROGRESS a half, NEEDS_IMPROVEMENT a quarter -- so a seeded database and a
-- database the application has been clicked through agree.
INSERT INTO student_batch_progress (student_id, batch_id, college_id, topics_total,
                                    topics_completed, topics_in_progress, topics_needs_work,
                                    weighted_percent, average_score, last_activity_at, recomputed_at)
SELECT
    tp.student_id,
    tp.batch_id,
    tp.college_id,
    count(*),
    count(*) FILTER (WHERE tp.status = 'COMPLETED'),
    count(*) FILTER (WHERE tp.status = 'IN_PROGRESS'),
    count(*) FILTER (WHERE tp.status = 'NEEDS_IMPROVEMENT'),
    round(sum(CASE tp.status
                  WHEN 'COMPLETED'         THEN 1.0
                  WHEN 'IN_PROGRESS'       THEN 0.5
                  WHEN 'NEEDS_IMPROVEMENT' THEN 0.25
                  ELSE 0 END) / count(*) * 100, 2),
    round(avg(tp.score), 2),
    max(tp.updated_at),
    now()
FROM topic_progress tp
GROUP BY tp.student_id, tp.batch_id, tp.college_id;

-- ---------------------------------------------------------------------------
-- 10. Feedback
-- ---------------------------------------------------------------------------
--
-- Both directions, because the table carries both and a screen that has only
-- ever shown one of them has only ever been half tested.

INSERT INTO feedback (batch_id, from_user_id, to_user_id, feedback_type, rating, comment, category, created_at)
SELECT b.id, fu.id, tu.id, v.feedback_type, v.rating, v.comment, v.category,
       now() - (v.days_ago || ' days')::interval
FROM (VALUES
    ('Data Analytics 2026', 'arun@hillview.test',   'aditi@hillview.test',
     'TRAINER_TO_STUDENT', 5, 'Window functions clicked immediately. Ready for the capstone.', 'Technical', 6),
    ('Data Analytics 2026', 'arun@hillview.test',   'manav@hillview.test',
     'TRAINER_TO_STUDENT', 2, 'Missed four sessions. Needs a catch-up plan before the Tableau module.', 'Attendance', 3),
    ('Data Analytics 2026', 'kavitha@hillview.test','ananya@hillview.test',
     'TRAINER_TO_STUDENT', 5, 'Her hypothesis-testing write-up was the clearest in the cohort.', 'Technical', 5),
    ('Data Analytics 2026', 'kavitha@hillview.test','rohan@hillview.test',
     'TRAINER_TO_STUDENT', 4, 'Strong in pandas, avoids SQL. Push him onto the joins exercises.', 'Technical', 8),
    ('Data Analytics 2026', 'aditi@hillview.test',  'arun@hillview.test',
     'STUDENT_TO_TRAINER', 5, 'The query-plan session was the most useful hour of the batch.', 'Teaching', 4),
    ('Data Analytics 2026', 'rohan@hillview.test',  'kavitha@hillview.test',
     'STUDENT_TO_TRAINER', 4, 'Good pace. More worked examples in the statistics module would help.', 'Teaching', 2),
    ('Backend Engineering 2026', 'arun@hillview.test', 'karthik@hillview.test',
     'TRAINER_TO_STUDENT', 4, 'Transaction boundaries understood. Now make him explain them out loud.', 'Technical', 7),
    ('Applied Data Science 2026', 'meera@northgate.test', 'aisha@northgate.test',
     'TRAINER_TO_STUDENT', 5, 'Best regression diagnostics in the batch.', 'Technical', 5),
    ('Applied Data Science 2026', 'pooja@northgate.test', 'meera@northgate.test',
     'STUDENT_TO_TRAINER', 4, 'Clear teaching. The sampling week went a little fast.', 'Teaching', 3)
) AS v(batch_name, from_email, to_email, feedback_type, rating, comment, category, days_ago)
JOIN batches b ON b.name = v.batch_name
JOIN users fu ON fu.email = v.from_email
JOIN users tu ON tu.email = v.to_email;

-- ---------------------------------------------------------------------------
-- 11. Placements
-- ---------------------------------------------------------------------------
--
-- Every status the table allows appears at least once, so the funnel on the
-- placement screen has a shape rather than a single bar.

INSERT INTO placements (student_id, company_id, status, applied_date, failure_reason, notes)
SELECT st.id, co.id, v.status, current_date - v.days_ago, v.failure_reason, v.notes
FROM (VALUES
    ('HIT2301', 'Trilytics Analytics', 'OFFER',     40, NULL, 'Analyst, Chennai. Offer accepted.'),
    ('HIT2309', 'Trilytics Analytics', 'OFFER',     38, NULL, 'Data analyst, Bengaluru.'),
    ('HIT2303', 'Trilytics Analytics', 'INTERVIEW', 12, NULL, 'Second round scheduled.'),
    ('HIT2305', 'Northwind Software',  'INTERVIEW', 10, NULL, 'Technical round on SQL and Spark.'),
    ('HIT2302', 'Trilytics Analytics', 'REJECTED',  30, 'No SQL beyond basic SELECT at the screening stage.', NULL),
    ('HIT2304', 'Northwind Software',  'APPLIED',    5, NULL, 'Backend role.'),
    ('HIT2308', 'Vayu Cloud Services', 'APPLIED',    4, NULL, 'Internship.'),
    ('HIT2310', 'Northwind Software',  'REJECTED',  25, 'Strong on databases, no reporting experience.', NULL),
    ('NGC2401', 'Deccan Data Labs',    'OFFER',     20, NULL, 'Junior data scientist, Pune.'),
    ('NGC2402', 'Deccan Data Labs',    'INTERVIEW',  8, NULL, 'Modelling round.')
) AS v(roll_number, company_name, status, days_ago, failure_reason, notes)
JOIN students st ON st.roll_number = v.roll_number
JOIN companies co ON co.name = v.company_name AND co.college_id = st.college_id;

-- ---------------------------------------------------------------------------
-- 12. Nothing was silently dropped
-- ---------------------------------------------------------------------------
--
-- Every list above is joined to the rows it names. An inner join that finds no
-- match does not fail -- it produces nothing, and the seed reports success with
-- a student short of a skill, a syllabus short of three topics, or a batch
-- nobody is enrolled in. That is the one way this file can be wrong while
-- appearing to work, so it is the one thing checked here, by name, before the
-- transaction is allowed to commit.

DO $$
DECLARE
    dropped text;
BEGIN
    SELECT string_agg(format('%s -> %s', v.roll_number, v.skill_name), '; ' ORDER BY v.roll_number)
      INTO dropped
      FROM demo_student_skill v
     WHERE NOT EXISTS (SELECT 1 FROM students st WHERE st.roll_number = v.roll_number)
        OR NOT EXISTS (SELECT 1 FROM skills sk WHERE sk.name = v.skill_name);
    IF dropped IS NOT NULL THEN
        RAISE EXCEPTION 'section 4 names a student or a skill that does not exist: %', dropped;
    END IF;

    SELECT string_agg(format('%s / %s', v.submodule_name, v.name), '; ' ORDER BY v.submodule_name)
      INTO dropped
      FROM demo_topic v
     WHERE NOT EXISTS (SELECT 1 FROM syllabus_submodules sm WHERE sm.name = v.submodule_name);
    IF dropped IS NOT NULL THEN
        RAISE EXCEPTION 'section 7 puts a topic under a submodule that does not exist: %', dropped;
    END IF;

    SELECT string_agg(format('%s / %s', v.batch_name, v.roll_number), '; ' ORDER BY v.batch_name)
      INTO dropped
      FROM demo_enrollment v
     WHERE NOT EXISTS (SELECT 1 FROM batches b WHERE b.name = v.batch_name)
        OR NOT EXISTS (SELECT 1 FROM students st WHERE st.roll_number = v.roll_number)
        OR NOT EXISTS (SELECT 1 FROM users u WHERE u.email = v.enrolled_by);
    IF dropped IS NOT NULL THEN
        RAISE EXCEPTION 'section 8 enrols somebody who does not exist, into a batch that may not either: %', dropped;
    END IF;

    -- A student in a batch belonging to another college would break every
    -- isolation claim in docs/SECURITY.md while looking like ordinary data.
    IF EXISTS (SELECT 1 FROM enrollments e
                 JOIN students st ON st.id = e.student_id
                WHERE st.college_id <> e.college_id) THEN
        RAISE EXCEPTION 'an enrolment crosses colleges';
    END IF;

    -- Section 3 promises exactly one student with no skills, and section 13
    -- relies on it: that student is the SKIPPED report.
    IF (SELECT count(*) FROM students s
         WHERE NOT EXISTS (SELECT 1 FROM student_skills ss WHERE ss.student_id = s.id)) <> 1 THEN
        RAISE EXCEPTION 'expected exactly one student with no skills, found %',
            (SELECT count(*) FROM students s
              WHERE NOT EXISTS (SELECT 1 FROM student_skills ss WHERE ss.student_id = s.id));
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 13. Ask for the analyses
-- ---------------------------------------------------------------------------
--
-- One PROFILE_UPDATED per student, written to the outbox exactly as
-- OutboxWriter would: the same envelope, the same routing key, the same schema
-- version. Nothing here talks to RabbitMQ. The running backend's relay picks
-- these up within its poll interval, publishes them, and the AI service
-- analyses each student and stores the report -- so the reports in a seeded
-- database were produced by the real pipeline rather than written here, and
-- the path from a change to a report is demonstrable rather than asserted.
--
-- DemoSeedTest checks this envelope against contracts/ai-events/v2 and against
-- EventType's current version, so raising the schema version fails the build
-- here too rather than leaving the seed quietly producing version 2 events that
-- nothing accepts.
--
-- Tarun Bhat is included despite having no skills. His analysis is the one that
-- comes back SKIPPED, and that report has to exist for his screen to explain
-- itself.

INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type,
                           schema_version, routing_key, payload, headers,
                           status, attempts, next_attempt_at, created_at)
SELECT
    gen_random_uuid(),
    'Student',
    s.id::text,
    'PROFILE_UPDATED',
    2,
    'ai.profile.updated',
    jsonb_build_object(
        -- eventId is filled in by the statement below. A LATERAL subquery
        -- generating it here looks correlated and is not: the planner sees no
        -- reference to `s`, evaluates gen_random_uuid() once, and every row
        -- gets the same id -- which the unique constraint on event_id catches,
        -- loudly, on the second row.
        'eventType',     'PROFILE_UPDATED',
        'schemaVersion', 2,
        'occurredAt',    to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
        'aggregateType', 'Student',
        'aggregateId',   s.id::text,
        'collegeId',     s.college_id,
        'traceId',       NULL,
        'payload',       jsonb_build_object('studentId', s.id)),
    jsonb_build_object('seededBy', 'scripts/db/demo-seed.sql'),
    'PENDING',
    0,
    now(),
    now()
FROM students s;

-- The envelope carries the same id as the row, because that id is also the AMQP
-- message_id and what the consumer deduplicates on. One id, everywhere.
UPDATE outbox_events
   SET payload = jsonb_set(payload, '{eventId}', to_jsonb(event_id::text), true)
 WHERE event_type = 'PROFILE_UPDATED' AND status = 'PENDING';

COMMIT;
