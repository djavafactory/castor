-- A published version is immutable. The only change it ever goes through is PUBLISHED -> SUPERSEDED when a newer
-- version is published (the row_version counter moves with it); its rows can never be deleted.
CREATE FUNCTION layout.layout_version_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status NOT IN ('PUBLISHED', 'SUPERSEDED') THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a published layout version cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'PUBLISHED' AND NEW.status = 'SUPERSEDED'
        AND ROW (NEW.id, NEW.port_id, NEW.version_no, NEW.crs, NEW.based_on_version_id, NEW.restored_from_version_id,
                 NEW.note, NEW.created_by, NEW.created_at, NEW.reviewed_by, NEW.published_by, NEW.published_at)
            IS NOT DISTINCT FROM
           ROW (OLD.id, OLD.port_id, OLD.version_no, OLD.crs, OLD.based_on_version_id, OLD.restored_from_version_id,
                OLD.note, OLD.created_by, OLD.created_at, OLD.reviewed_by, OLD.published_by, OLD.published_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a published layout version cannot be modified' USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER layout_version_guard
    BEFORE UPDATE OR DELETE ON layout.layout_version
    FOR EACH ROW EXECUTE FUNCTION layout.layout_version_guard();
