import re

with open("src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagCausalGraph.kt", "r") as f:
    text = f.read()

# Replace any duplicated/malformed imports with a single clean block
text = re.sub(r'import borg\.trikeshed\.job\.ContentId\.Companion\.of\n', '', text)
text = re.sub(r'import borg\.trikeshed\.job\.ContentId\n', '', text)
text = re.sub(r'import borg\.trikeshed\.job\.CausalNode\.Companion\.of\n', '', text)
text = re.sub(r'import borg\.trikeshed\.job\.CausalNode\n', '', text)
text = re.sub(r'import borg\.trikeshed\.job\.project\n', '', text)
text = re.sub(r'import borg\.trikeshed\.job\.CasStore\n', 'import borg.trikeshed.job.CasStore\nimport borg.trikeshed.job.ContentId\nimport borg.trikeshed.job.ContentId.Companion.of\nimport borg.trikeshed.job.project\nimport borg.trikeshed.job.CausalNode\n', text)


with open("src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagCausalGraph.kt", "w") as f:
    f.write(text)
