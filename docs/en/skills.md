# Skills

Skills are reusable Markdown instruction files stored locally by Avro.

When **Allow skill access** is enabled, each new generation freezes a compact catalog containing only skill file names and descriptions. The model can then use the skill tools to list, read, create, edit, or delete files. Skill bodies are never inserted into every request automatically; the model reads a relevant file on demand.

Avro has no “active skill” file or active-skill toggle. Editing a skill affects later generations, while an already admitted generation keeps its frozen catalog and tool policy.

Skills are included in native exports and automatic backups. Import can merge them or replace the local skill database according to the selected import mode.
