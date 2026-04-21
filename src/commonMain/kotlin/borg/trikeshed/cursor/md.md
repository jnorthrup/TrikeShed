Skip to content

Search Gists
Search...
All gists
Back to GitHub
Editing beforeafter.md
Secret
Gist description…
beforeafter.md


Spaces

4

Soft wrap
116
​
117
Cursor --> RowVec : Series of
118
RowVec <|-- BlockRowVec : specializes
119
RowVec <|-- DocRowVec : specializes
120
RowVec <|-- ViewRowVec : specializes
121
RowVec <|-- JsonRowVec : specializes
122
RowVec <|-- YamlRowVec : specializes
123
RowVec <|-- BlobRowVec : specializes
124
​
125
BlockRowVec --> RowVec : children (sealed block contains)
126
​
127
BlackboardOverlay --> RowVec : decorates parent or child
128
BlackboardOverlay --> OverlayRole : role
129
​
130
Tensor~T~ ..> Shape : uses
131
BlockRowVec ..> Tensor~T~ : optional lowering path
132
​
133
note for BlockRowVec "mutable -> sealed\none writer / sealed\nsealing = sync boundary"
134
note for BlobRowVec "zero-length parent is valid\nmeaning deferred into children"
135
note for Tensor~T~ "NOT semantic center\noptional exec backend only"
136
```
137
​
138
---
139
​
140
## Key Delta
141
​
142
| Aspect | Before | After |
143
|---|---|---|
144
| Cursor typedef | `Series<RowVec>` | `Series<RowVec>` (unchanged) |
145
| RowVec shape | flat `Any?` column bag | sealed family root with lazy `.child` |
146
| Nesting | none | arbitrary depth via `.child: Series<RowVec>?` |
147
| Block boundary | none | `BlockRowVec` mutable → sealed (DuckDB-style) |
148
| Locking | none | one writer per mutable block; sealed = read-many |
149
| Blob / doc / JSON | not modeled | `BlobRowVec`, `DocRowVec`, `JsonRowVec`, `YamlRowVec` |
150
| Blackboard | not connected | `BlackboardOverlay` decorates any family row |
151
| Zero-length rows | meaningless | first-class (deferred child meaning) |
152
| Tensor | not present | optional lowering only; `Shape = Series<Int>` |
153
| View expansion | flat result | `ViewRowVec` with deferred `doc` child |
154
​
No file chosen
Attach files by selecting or pasting them.
Use Control+Shift+m to toggle the tab key moving focus.
Commit email: jnorthrup@users.noreply.github.com

Footer
© 2026 GitHub, Inc.
Footer navigation
Terms
Privacy
Security
Status
Community
Docs
Contact
Manage cookies
Do not share my personal information
