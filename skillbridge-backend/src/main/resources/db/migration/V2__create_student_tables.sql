-- Students table
CREATE TABLE IF NOT EXISTS students (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT UNIQUE NOT NULL,
    college_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    roll_number VARCHAR(50) NOT NULL,
    degree VARCHAR(100),
    branch VARCHAR(100),
    year INTEGER,
    phone VARCHAR(20),
    github_url VARCHAR(255),
    portfolio_url VARCHAR(255),
    resume_url VARCHAR(255),
    bio TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_students_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_students_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE,
    CONSTRAINT uk_students_roll_number_college UNIQUE (roll_number, college_id)
);

-- Skills catalog (global)
CREATE TABLE IF NOT EXISTS skills (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    category VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Student skills (many-to-many with proficiency)
CREATE TABLE IF NOT EXISTS student_skills (
    student_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    proficiency_level INTEGER NOT NULL CHECK (proficiency_level BETWEEN 1 AND 5),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (student_id, skill_id),
    CONSTRAINT fk_student_skills_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT fk_student_skills_skill FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE
);

-- Student projects
CREATE TABLE IF NOT EXISTS student_projects (
    id BIGSERIAL PRIMARY KEY,
    student_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    technologies TEXT,
    project_url VARCHAR(255),
    github_url VARCHAR(255),
    start_date DATE,
    end_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_student_projects_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE
);

-- Indexes for performance (IF NOT EXISTS)
CREATE INDEX IF NOT EXISTS idx_students_college_id ON students(college_id);
CREATE INDEX IF NOT EXISTS idx_students_user_id ON students(user_id);
CREATE INDEX IF NOT EXISTS idx_student_skills_student_id ON student_skills(student_id);
CREATE INDEX IF NOT EXISTS idx_student_skills_skill_id ON student_skills(skill_id);
CREATE INDEX IF NOT EXISTS idx_student_projects_student_id ON student_projects(student_id);

-- Pre-populate common skills (ON CONFLICT DO NOTHING to handle existing data)
INSERT INTO skills (name, category) VALUES 
    ('Java', 'Programming'),
    ('Python', 'Programming'),
    ('JavaScript', 'Programming'),
    ('TypeScript', 'Programming'),
    ('C++', 'Programming'),
    ('C#', 'Programming'),
    ('Go', 'Programming'),
    ('Rust', 'Programming'),
    ('React', 'Framework'),
    ('Angular', 'Framework'),
    ('Vue.js', 'Framework'),
    ('Spring Boot', 'Framework'),
    ('Django', 'Framework'),
    ('Flask', 'Framework'),
    ('Node.js', 'Framework'),
    ('Express.js', 'Framework'),
    ('PostgreSQL', 'Database'),
    ('MongoDB', 'Database'),
    ('MySQL', 'Database'),
    ('Redis', 'Database'),
    ('Cassandra', 'Database'),
    ('Docker', 'DevOps'),
    ('Kubernetes', 'DevOps'),
    ('Jenkins', 'DevOps'),
    ('AWS', 'Cloud'),
    ('Azure', 'Cloud'),
    ('Google Cloud', 'Cloud'),
    ('Git', 'Tools'),
    ('Linux', 'Tools'),
    ('REST API', 'Backend'),
    ('GraphQL', 'Backend'),
    ('Microservices', 'Backend'),
    ('HTML/CSS', 'Frontend'),
    ('Tailwind CSS', 'Frontend'),
    ('Bootstrap', 'Frontend'),
    ('Machine Learning', 'AI/ML'),
    ('Deep Learning', 'AI/ML'),
    ('TensorFlow', 'AI/ML'),
    ('PyTorch', 'AI/ML'),
    ('Data Structures', 'Computer Science'),
    ('Algorithms', 'Computer Science'),
    ('System Design', 'Computer Science'),
    ('Agile', 'Methodology'),
    ('Scrum', 'Methodology')
ON CONFLICT (name) DO NOTHING;

