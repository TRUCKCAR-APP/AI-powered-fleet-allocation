-- Síntesis en lenguaje claro de cada decisión (quién se eligió y por qué se descartó a los demás) para la torre.
ALTER TABLE allocation_decisions ADD COLUMN IF NOT EXISTS decision_explanation VARCHAR(2000);
