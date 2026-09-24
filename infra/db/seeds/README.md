# Bootstrapping the first Superadmin

Accounts are **not** self-registered. The first Superadmin is created by a developer during setup.

## Rules

- Never store or commit a plaintext password.
- Hash the password in the application (or a one-off tool), then insert only the hash.
- Use `role = 'superadmin'` and `created_by = NULL`.
- Prefer Bcrypt (cost factor 10–12) for this project; Spring Security’s `BCryptPasswordEncoder` matches later API work.

## 1. Generate a password hash (examples)

**Python (passlib / bcrypt):**

```bash
python3 -c "import bcrypt; print(bcrypt.hashpw(b'YOUR_PASSWORD_HERE', bcrypt.gensalt(rounds=12)).decode())"
```

**htpasswd (Apache utils):**

```bash
htpasswd -nbBC 12 x 'YOUR_PASSWORD_HERE' | cut -d: -f2
```

**Java (once Spring Security is on the classpath):**

```java
System.out.println(new BCryptPasswordEncoder(12).encode("YOUR_PASSWORD_HERE"));
```

Replace `YOUR_PASSWORD_HERE` with a strong secret. Do not commit the password or a hash derived from a known demo password into git if the database is shared.

## 2. Insert the Superadmin (pgAdmin or `psql`)

After running [`../migrations/V1__init.sql`](../migrations/V1__init.sql):

```sql
INSERT INTO users (
    email,
    password_hash,
    role,
    display_name,
    created_by,
    is_active,
    password_changed_at
) VALUES (
    'superadmin@lab.local',          -- change to your real admin email (lowercase)
    '<PASTE_BCRYPT_HASH_HERE>',      -- output from step 1
    'superadmin',
    'Lab Superadmin',
    NULL,
    TRUE,
    NOW()
);
```

## 3. Verify

```sql
SELECT id, email, role, created_by, is_active, created_at
FROM users
WHERE role = 'superadmin';
```

You should see one row with `created_by IS NULL`. Additional Superadmins can be inserted the same way later (still not via the normal UI).

## 4. What not to do

- Do not put plaintext passwords in SQL files.
- Do not commit real production hashes for known passwords.
- Do not hard-delete users who appear in `created_by`; set `is_active = FALSE` instead.
