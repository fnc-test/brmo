--
-- upgrade Oracle RSGB datamodel van 2.2.0 naar 2.2.1
--

WHENEVER SQLERROR EXIT SQL.SQLCODE

-- BRMO-130 / GH #1231 De BAG 2 views moeten vervangen worden
drop view vb_adresseerbaar_object_geometrie;
drop view vb_verblijfsobject_adres;
drop view vb_standplaats_adres;
drop view vb_ligplaats_adres;
drop view vb_adres;

DECLARE
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE brmo_metadata(naam VARCHAR2(255 CHAR) NOT NULL,waarde VARCHAR2(255 CHAR),PRIMARY KEY (naam));';
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; ELSE RAISE; END IF;
END;
MERGE INTO brmo_metadata USING DUAL ON (naam = 'brmoversie') WHEN NOT MATCHED THEN INSERT (naam) VALUES('brmoversie');

-- onderstaande dienen als laatste stappen van een upgrade uitgevoerd
INSERT INTO brmo_metadata (naam,waarde) SELECT 'upgrade_2.2.0_naar_2.2.1','vorige versie was ' || waarde FROM brmo_metadata WHERE naam='brmoversie';
-- versienummer update
UPDATE brmo_metadata SET waarde='2.2.1' WHERE naam='brmoversie';