# PropertyType Decision

Decision: Remove aspirational PropertyType cases (de-stub path). T22-T27 are not committed.

## Gap Matrix

| PropertyType | Producer | Consumer | Test | Downstream Treatment |
|---|---|---|---|---|
| TITLE | Yes | Yes | Yes | Yes |
| TEXT | Yes | Yes | Yes | Yes |
| NUMBER | Yes | Yes | Yes | Yes |
| SELECT | Yes | Yes | Yes | Yes |
| MULTI_SELECT | No | No | No | No |
| DATE | Yes | Yes | Yes | Yes |
| PEOPLE | Yes | No | Yes | No |
| FILES | Yes | No | Yes | No |
| CHECKBOX | Yes | Yes | Yes | Yes |
| URL | No | No | No | No |
| EMAIL | No | No | No | No |
| PHONE_NUMBER | No | No | No | No |
| FORMULA | No | No | No | No |
| RELATION | No | No | No | No |
| ROLLUP | No | No | No | No |
| CREATED_TIME | No | No | No | No |
| CREATED_BY | No | No | No | No |
| LAST_EDITED_TIME | No | No | No | No |
| LAST_EDITED_BY | No | No | No | No |

## Decision
We are removing FORMULA, RELATION, ROLLUP, PEOPLE, FILES, MULTI_SELECT, URL, EMAIL, PHONE_NUMBER, CREATED_TIME, CREATED_BY, LAST_EDITED_TIME, LAST_EDITED_BY from the `PropertyType` enum to keep the API surface honest. Comment notes will be kept in the enum definition.

Note that while `PEOPLE` and `FILES` currently have producers and some tests for those producers, they are missing full downstream treatment, so we are choosing to remove them as part of the de-stubbing process, until they can be fully implemented.
