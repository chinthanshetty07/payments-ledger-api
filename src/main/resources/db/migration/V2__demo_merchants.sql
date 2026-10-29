-- Two demo merchants. Only SHA-256 hashes are stored. The matching test keys are
-- demo_key_merchant_a / demo_key_merchant_b: public demo values, not secrets.
INSERT INTO merchants (id, name, api_key_hash) VALUES
  ('m_a', 'Demo Merchant A', '8dd0e546293534b5cac9c1924e445f4a244f884485eac14e8b4e2f689f195f30'),
  ('m_b', 'Demo Merchant B', 'd722891bcb559f3c1976d86817a838c965c506eacfa8a0743e07b00a0607c6ae');
