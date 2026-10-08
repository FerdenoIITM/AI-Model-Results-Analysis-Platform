-- Minimal assertion helpers. Each passing check prints NOTICE "ok - ...";
-- each failing check raises ERROR "not ok - ..." so run.sh can count both.

CREATE SCHEMA IF NOT EXISTS t;

CREATE OR REPLACE FUNCTION t.ok(cond boolean, description text) RETURNS void AS $$
BEGIN
    IF cond IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'not ok - %', description;
    END IF;
    RAISE NOTICE 'ok - %', description;
END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION t.eq(actual anyelement, expected anyelement, description text) RETURNS void AS $$
BEGIN
    IF actual IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'not ok - % (expected %, got %)', description, expected, actual;
    END IF;
    RAISE NOTICE 'ok - %', description;
END
$$ LANGUAGE plpgsql;

-- Statement must fail with the given SQLSTATE. Its effects are rolled back either way.
CREATE OR REPLACE FUNCTION t.throws(stmt text, expected_state text, description text) RETURNS void AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE = expected_state THEN
            RAISE NOTICE 'ok - %', description;
            RETURN;
        END IF;
        RAISE EXCEPTION 'not ok - % (expected SQLSTATE %, got %: %)', description, expected_state, SQLSTATE, SQLERRM;
    END;
    RAISE EXCEPTION 'not ok - % (statement succeeded, expected SQLSTATE %)', description, expected_state;
END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION t.lives(stmt text, description text) RETURNS void AS $$
BEGIN
    EXECUTE stmt;
    RAISE NOTICE 'ok - %', description;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'not ok - % (%: %)', description, SQLSTATE, SQLERRM;
END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION t.plan(query text) RETURNS text AS $$
DECLARE
    line record;
    result text := '';
BEGIN
    FOR line IN EXECUTE 'EXPLAIN ' || query LOOP
        result := result || line."QUERY PLAN" || E'\n';
    END LOOP;
    RETURN result;
END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION t.new_user(email text, role user_role DEFAULT 'member', creator uuid DEFAULT NULL)
RETURNS uuid AS $$
    INSERT INTO users (email, password_hash, role, display_name, created_by)
    VALUES (email, '$2a$04$hash', role, 'Test', creator)
    RETURNING id;
$$ LANGUAGE sql;

CREATE OR REPLACE FUNCTION t.new_result(model text DEFAULT 'M', conf double precision DEFAULT NULL)
RETURNS uuid AS $$
    INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, confidence_accuracy)
    VALUES (model, '1.0', '20260101000000', 'x.json', 2, 'h', conf)
    RETURNING id;
$$ LANGUAGE sql;
