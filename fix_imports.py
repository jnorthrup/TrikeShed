with open("src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagCausalGraph.kt", "r") as f:
    text = f.read()

import re

# Remove duplicate imports
text = re.sub(r'import borg.trikeshed.job.ContentId\n', '', text)
text = re.sub(r'import borg.trikeshed.job.project\n', '', text)
text = re.sub(r'import borg.trikeshed.job.CausalNode.Companion.of\n', '', text)
text = re.sub(r'import borg.trikeshed.job.CausalNode\n', '', text)

# Add them back exactly once
imports = """
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.ContentId.Companion.of
import borg.trikeshed.job.project
import borg.trikeshed.job.CausalNode
"""
text = text.replace("import borg.trikeshed.job.CasStore", "import borg.trikeshed.job.CasStore" + imports)

with open("src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagCausalGraph.kt", "w") as f:
    f.write(text)
