--
-- PostgreSQL database dump
--

\restrict MlZZ0mz1aG6Y2iwJpPfZMYJJvOMaTtX8eMixKnPjOCoxj3nL3aVyHwHh17e9Bmd

-- Dumped from database version 17.11
-- Dumped by pg_dump version 17.11

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: assets; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.assets (id, asset_status, asset_type, created_at, current_price, name, symbol, updated_at) VALUES (6, 'INACTIVE', 'STOCK', '2026-10-06 12:07:20.813153', 125.00, 'Test Corporation', 'TEST ASSET', '2026-10-28 21:35:59.419999');
INSERT INTO public.assets (id, asset_status, asset_type, created_at, current_price, name, symbol, updated_at) VALUES (3, 'INACTIVE', 'STOCK', '2026-10-02 12:00:49.370412', 100.00, 'Old Company', 'OLD', '2026-11-10 20:13:15.916005');
INSERT INTO public.assets (id, asset_status, asset_type, created_at, current_price, name, symbol, updated_at) VALUES (1, 'ACTIVE', 'STOCK', '2026-10-02 12:00:49.370412', 461.09, 'Apple Inc.', 'AAPL', '2026-11-15 14:06:27.32299');
INSERT INTO public.assets (id, asset_status, asset_type, created_at, current_price, name, symbol, updated_at) VALUES (2, 'ACTIVE', 'STOCK', '2026-10-02 12:00:49.370412', 590.15, 'Tesla Inc.', 'TSLA', '2026-11-15 14:06:27.32299');
INSERT INTO public.assets (id, asset_status, asset_type, created_at, current_price, name, symbol, updated_at) VALUES (4, 'ACTIVE', 'STOCK', '2026-10-02 23:20:06.996134', 478.95, 'Microsoft Corporation', 'MSFT', '2026-11-15 14:06:27.32299');
INSERT INTO public.assets (id, asset_status, asset_type, created_at, current_price, name, symbol, updated_at) VALUES (5, 'ACTIVE', 'STOCK', '2026-10-05 17:20:32.590798', 117.14, 'Amazon.com Inc.', 'AMZN', '2026-11-15 14:06:27.338244');


--
-- Name: assets_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.assets_id_seq', 6, true);


--
-- PostgreSQL database dump complete
--

\unrestrict MlZZ0mz1aG6Y2iwJpPfZMYJJvOMaTtX8eMixKnPjOCoxj3nL3aVyHwHh17e9Bmd

