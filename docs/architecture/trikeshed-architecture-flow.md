# TrikeShed Architecture Flow

## Overview
```
facetted-cursors → confix → user signals → kanban-idea-grapher → lcnc → windowing-abstraction → forge user interface
```

---

## 1. Facetted Cursors → Confix (Cursor Gateway)

```mermaid
flowchart LR
    subgraph "Facetted Cursors Layer"
        FC1["Multi-faceted RowVec<br/>(values + ColumnMeta↻ supplier)"]
        FC2["Cursor = Series<RowVec><br/>Indexed columnar storage"]
        FC3["FacetedRow = Join<RowVec, Any><br/>Row with lazy facets"]
        FC4["ConfixIndex = FacetedRow<Any><br/>Cursor with structural index"]
    end

    subgraph "Confix Parser (Cursor Gateway)"
        CP1["Syntax.scan()<br/>JSON / CBOR / YAML → Cursor"]
        CP2["scan0() → Join<Cursor, FlatIndex><br/>Span tree: open/close/tag/depth"]
        CP3["buildTree() → Cursor<br/>Nested RowVec hierarchy"]
        CP4["ConfixDoc = Join<ConfixIndex, Series<Byte>><br/>Facade + body (zero-copy reload)"]
    end

    subgraph "Navigation & Query"
        N1["ConfixCell = Join<RowVec, Series<Byte>><br/>Row + source bytes"]
        N2["RowVec.step(key/src) → RowVec?<br/>Object field / Array index"]
        N3["ConfixDoc.getAt(path) → RowVec<br/>Compound navigation"]
        N4["ConfixDoc.reify(tokenIdx) → Any<br/>Lazy materialization"]
        N5["JsPath = Series<Join<String, Int>><br/>Typed path segments"]
        N6["BlackBoardEntry<br/>doc + role + timestamp + provenance"]
    end

    FC1 --> CP1
    FC2 --> CP1
    FC3 --> CP4
    FC4 --> CP1
    CP1 --> CP2
    CP2 --> CP3
    CP3 --> CP4
    CP4 --> N1
    N1 --> N2
    N2 --> N3
    N3 --> N4
    N4 --> N5
    N5 --> N6

    classDef cursorLayer fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px;
    classDef confixLayer fill:#e3f2fd,stroke:#1565c0,stroke-width:2px;
    classDef navLayer fill:#fff3e0,stroke:#e65100,stroke-width:2px;

    class FC1,FC2,FC3,FC4 cursorLayer;
    class CP1,CP2,CP3,CP4 confixLayer;
    class N1,N2,N3,N4,N5,N6 navLayer;
```

---

## 2. Confix → User Signals (Signal Algebra)

```mermaid
flowchart LR
    subgraph "Confix Output"
        CO1["ConfixDoc<br/>Facade (immutable index) + Body (swappable bytes)"]
        CO2["BlackBoardEntry<br/>role: OBSERVATION│DERIVED│AGGREGATE│TYPEDEF_CHAIN"]
        CO3["ConfixRole enum<br/>OBSERVATION, DERIVED, AGGREGATE, TYPEDEF_CHAIN, POINTCUT_STATS"]
    end

    subgraph "Signal Algebra (libs/user-signals)"
        SA1["Signal<T><br/>value + changes: Flow<T>"]
        SA2["SignalSource<T> : Signal<T><br/>emit() / emitSuspend()"]
        SA3["MappedSignal / CombinedSignal /<br/>FilteredSignal / SampledSignal"]
    end

    subgraph "0-Dimensional Signals (Idiot Lights)"
        D0_1["Toggle : Signal<Boolean><br/>isOn, toggle(), turnOn/Off()"]
        D0_2["IdiotLight : Signal<Boolean><br/>isLit, flash(), pulse()"]
        D0_3["MomentaryButton : Signal<Boolean><br/>isPressed, press/release/tap()"]
        D0_4["RadioToggle<T> : Signal<T><br/>selected, options, select/clear()"]
    end

    subgraph "1-Dimensional Signals (Sliders/Knobs)"
        D1_1["Slider : Signal<Double><br/>min/max/step, normalized, setValue()"]
        D1_2["Knob : Signal<Double><br/>detents, radians, rotateBy(), snapToDetent()"]
        D1_3["Dial<T> : Signal<T><br/>positions[], index, next/prev/goto()"]
        D1_4["LevelMeter : Signal<Double><br/>level, peak, peakHoldMillis, setLevel()"]
    end

    subgraph "Text Field Signal"
        TF["TextField : Signal<TextFieldState><br/>text, caret, selection, focused, committed"]
    end

    subgraph "Factory Functions"
        F1["toggle(), idiotLight(), momentaryButton()"]
        F2["radioToggle(), slider(), knob()"]
        F3["dial(), levelMeter(), textField()"]
    end

    CO1 --> SA1
    CO2 --> SA1
    CO3 --> SA1
    SA1 --> SA2
    SA2 --> SA3
    SA3 --> D0_1
    SA3 --> D0_2
    SA3 --> D0_3
    SA3 --> D0_4
    SA3 --> D1_1
    SA3 --> D1_2
    SA3 --> D1_3
    SA3 --> D1_4
    SA3 --> TF
    SA3 --> F1
    SA3 --> F2
    SA3 --> F3

    classDef confixOut fill:#e3f2fd,stroke:#1565c0;
    classDef signalBase fill:#fce4ec,stroke:#c2185b;
    classDef zeroDim fill:#f3e5f5,stroke:#7b1fa2;
    classDef oneDim fill:#e8eaf6,stroke:#303f9f;
    classDef textSig fill:#fff8e1,stroke:#f57f17;
    classDef factory fill:#e0f2f1,stroke:#00695c;

    class CO1,CO2,CO3 confixOut;
    class SA1,SA2,SA3 signalBase;
    class D0_1,D0_2,D0_3,D0_4 zeroDim;
    class D1_1,D1_2,D1_3,D1_4 oneDim;
    class TF textSig;
    class F1,F2,F3 factory;
```

---

## 3. User Signals → Kanban / Idea Grapher

```mermaid
flowchart LR
    subgraph "User Signals Input"
        US1["Signal<T> algebra<br/>0D: Toggle, IdiotLight, Button, Radio"]
        US2["Signal<T> algebra<br/>1D: Slider, Knob, Dial, LevelMeter"]
        US3["TextField : Signal<TextFieldState>"]
    end

    subgraph "Kanban Types (libs/forge/KanbanTypes.kt)"
        KT1["KanbanBoard<br/>id, name, columns[], cards[], swimlanes[]"]
        KT2["KanbanColumn<br/>id, name, order, wipLimit"]
        KT3["KanbanCard<br/>id, title, desc, columnId, order, assignee,<br/>priority, dependencies[], tags, metadata"]
        KT4["Swimlane<br/>id, name, color, cardIds[]"]
        KT5["CardPriority enum<br/>LOW, MEDIUM, HIGH, CRITICAL"]
    end

    subgraph "CascadeGraph Visualization"
        CG1["CascadeGraph<br/>nodes: CascadeNode[], edges: CascadeEdge[]"]
        CG2["CascadeNode<br/>id, type: SOURCE/MAP/FILTER/REDUCE/JOIN/SINK,<br/>label, config: Map<String,String>"]
        CG3["CascadeEdge<br/>from, to, dataFlow: String"]
        CG4["CascadeStageType enum<br/>SOURCE, MAP, FILTER, REDUCE, JOIN, SINK"]
    end

    subgraph "Mermaid / Graphviz Rendering"
        MR1["KanbanBoard.toMermaid()<br/>graph LR with shaped nodes"]
        MR2["KanbanBoard.toDot()<br/>digraph with styled nodes/edges"]
        MR3["ForgeWorkflow.toShapeDimensionDiagram()<br/>17 PatchCableShapeDimension annotations"]
    end

    subgraph "Patch Cable / Module System"
        PC1["PatchCableShapeDimension enum (17 dims)<br/>SIGNAL_FLOW, CONTROL_FLOW, STATE_MUTATION,<br/>PARALLELISM, EXTERNAL_IO, TEMPORAL, STOCHASTIC,<br/>COMPOSITE, FEEDBACK, MODULATION, QUANTIZATION,<br/>SPATIAL, SPECTRAL, ENVELOPE, SEQUENCE,<br/>POLYPHONIC, GRANULAR, HYBRID"]
        PC2["WorkflowStep.getPatchCableShapeDimensions()<br/>Maps step type → Set<Dimension>"]
        PC3["ModuleSpec<br/>id, moduleType, inputPorts[], outputPorts[],<br/>parameterPorts[], position"]
        PC4["ModuleType enum (22 types)<br/>LLM_CALL, CODE_EXECUTION, AGENT_INVOCATION,<br/>CASCADE_EXECUTION, MAP, REDUCE, FILTER,<br/>OSCILLATOR, GATE, MIXER, SWITCH,..."]
        PC5["PatchCable<br/>source→dest PortAddress, transform,<br/>state, routing, latency"]
        PC6["PatchBay<br/>modules + cables + layers (SIGNAL/CONTROL/...)<br/>autoLayout(algorithm)"]
    end

    US1 --> KT1
    US2 --> KT1
    US3 --> KT1
    KT1 --> KT2
    KT1 --> KT3
    KT1 --> KT4
    KT1 --> KT5
    KT1 --> CG1
    CG1 --> CG2
    CG1 --> CG3
    CG2 --> CG4
    CG1 --> MR1
    CG1 --> MR2
    KT3 --> MR3
    MR3 --> PC1
    PC1 --> PC2
    PC2 --> PC3
    PC3 --> PC4
    PC5 --> PC6
    PC3 --> PC6

    classDef userSig fill:#fce4ec,stroke:#c2185b;
    classDef kanban fill:#e3f2fd,stroke:#1565c0;
    classDef cascade fill:#fff3e0,stroke:#e65100;
    classDef render fill:#e8f5e9,stroke:#2e7d32;
    classDef patch fill:#f3e5f5,stroke:#7b1fa2;

    class US1,US2,US3 userSig;
    class KT1,KT2,KT3,KT4,KT5 kanban;
    class CG1,CG2,CG3,CG4 cascade;
    class MR1,MR2,MR3 render;
    class PC1,PC2,PC3,PC4,PC5,PC6 patch;
```

---

## 4. Kanban / Idea Grapher → LCNC (Linear Confix/Columnar)

```mermaid
flowchart LR
    subgraph "LCNC Grid (libs/lcnc/LcncGrid.kt)"
        LG1["LcncGrid<br/>cursor: Cursor + srcDocs: Series<ConfixDoc>"]
        LG2["rowCount = cursor.size"]
        LG3["projectColumns(indices: IntArray)<br/>Linear array map, no reflection"]
        LG4["addFormulaColumn(formula)<br/>Lazy formula projection"]
        LG5["page(start, end)<br/>O(1) pagination via range view"]
    end

    subgraph "LCNC Reduction Algebra (libs/lcnc/reduction/)"
        LR1["LcncReduction<Key, Val, Acc, Out><br/>keyAlg + valueAlg + phaseAlg + carrierAlg"]
        LR2["KeyAlg<Key><br/>extractor, hierarchy, order"]
        LR3["ValueAlg<Val, Acc><br/>folder, merger, initial"]
        LR4["PhaseAlg<br/>mapPhase → reducePhase → rereducePhase → formatOutput"]
        LR5["CarrierAlg<br/>carrier: Any → ReductionCarrier"]
    end

    subgraph "Pre-configured Reductions (LcncReductions.kt)"
        PR1["forgeCascade(keyHierarchy, metrics)<br/>Key=List<String>, Val=Map,<br/>Acc=MultiMetricAccumulator, Out=List<CascadeOutputRow>"]
        PR2["confixParse()<br/>Key=ConfixStructuralKey, Val=Byte,<br/>Acc=TreeBuilderState, Out=Cursor"]
        PR3["crmsFold()<br/>Key=Int (callsiteHash), Val=TraceEvent,<br/>Acc=ConflictCell, Out=List<ConflictCell>"]
    end

    subgraph "Supporting Algebras"
        SA1["LcncKeyAlg<br/>confixStructuralKey(), naturalKeyOrder()"]
        SA2["LcncValueAlg<br/>forgeMultiMetricReducer(), forgeMerger(),<br/>crmsPairAndEigsort(), crmsMerger()"]
        SA3["LcncPhaseAlg<br/>forgePhaseAlg, confixPhaseAlg, crmsPhaseAlg"]
        SA4["LcncCarrierAlg<br/>seriesCarrierAlg, arrayCarrier, ringCarrier"]
    end

    LG1 --> LR1
    LG2 --> LR1
    LG3 --> LR1
    LG4 --> LR1
    LG5 --> LR1
    LR1 --> LR2
    LR1 --> LR3
    LR1 --> LR4
    LR1 --> LR5
    LR1 --> PR1
    LR1 --> PR2
    LR1 --> PR3
    PR1 --> SA1
    PR1 --> SA2
    PR1 --> SA3
    PR1 --> SA4
    PR2 --> SA1
    PR2 --> SA3
    PR2 --> SA4
    PR3 --> SA1
    PR3 --> SA2
    PR3 --> SA3
    PR3 --> SA4

    classDef grid fill:#e3f2fd,stroke:#1565c0;
    classDef reduction fill:#e8f5e9,stroke:#2e7d32;
    classDef preconf fill:#fff3e0,stroke:#e65100;
    classDef support fill:#f3e5f5,stroke:#7b1fa2;

    class LG1,LG2,LG3,LG4,LG5 grid;
    class LR1,LR2,LR3,LR4,LR5 reduction;
    class PR1,PR2,PR3 preconf;
    class SA1,SA2,SA3,SA4 support;
```

---

## 5. LCNC → Windowing Abstraction (window-toolkit)

```mermaid
flowchart LR
    subgraph "Window Toolkit Core (libs/window-toolkit/)"
        WT1["WindowContextElement<br/>signalContextInstance + open/close"]
        WT2["SignalContextElement<br/>SignalFactory + signal algebra"]
        WT3["SignalFactory<br/>toggle, light, button, radio, slider,<br/>knob, dial, level, textField"]
        WT4["WindowShell<br/>components registry + signals factory"]
    end

    subgraph "DSL (WindowToolkitDsl.kt)"
        D1["windowContext(block)<br/>Entry point - creates WindowShell"]
        D2["panel(block)<br/>SignalTemplate builder"]
        D3["SignalTemplateBuilder<br/>template(), beside(), above(), overlay(), whenVisible()"]
        D4["RenderPipeline / ConsoleRenderer<br/>pipeline(), live(), quickTui()"]
    end

    subgraph "Signal Algebra (internal/)"
        SA1["SignalAlgebra<br/>beside(), above(), overlay(), whenVisible()"]
        SA2["ComponentRegistry<br/>Declarative pipeline building"]
        SA3["SignalTemplate<br/>Composable signal component"]
    end

    subgraph "Widgets (widgets/)"
        W1["Widget<T>: state → RenderContext → Unit"]
        W2["PanelWidget: children[], layout, padding"]
        W3["Layout: Column/Row/Grid/Flow/Stack"]
        W4["ToggleWidget, SliderWidget, KnobWidget,<br/>RadioWidget, LevelMeterWidget, TextFieldWidget"]
        W5["LabelWidget, ButtonWidget, GroupWidget,<br/>TabsWidget, ListWidget, CanvasWidget"]
    end

    subgraph "Window & Platform (window/)"
        W6["Window interface<br/>title, size, position, open/close/resize/move"]
        W7["WindowFactory: create(), createChild()"]
        W8["SwingWindowFactory / SwingWindow<br/>JFrame implementation"]
        W9["InputDispatcher<br/>Mouse/Key/Scroll → widget handlers"]
        W10["RenderContext<br/>drawRect, drawText, drawLine, drawCircle"]
    end

    subgraph "LCNC Integration"
        LC1["LCNC Grid as signal source<br/>cursor.page() → paged widgets"]
        LC2["LCNC reductions as transforms<br/>mapPhase/reducePhase → Signal.map()"]
        LC3["ConfixDoc.body swap → Signal.emit()<br/>Zero-copy reload triggers UI update"]
        LC4["ConfixRole → signal metadata<br/>OBSERVATION/DERIVED/AGGREGATE"]
    end

    WT1 --> WT2
    WT2 --> WT3
    WT3 --> WT4
    WT4 --> D1
    D1 --> D2
    D2 --> D3
    D3 --> D4
    D3 --> SA1
    D4 --> SA2
    D4 --> SA3
    D4 --> W1
    W1 --> W2
    W2 --> W3
    W1 --> W4
    W1 --> W5
    W4 --> W6
    W6 --> W7
    W7 --> W8
    W8 --> W9
    W9 --> W10
    LC1 --> WT4
    LC2 --> WT4
    LC3 --> WT2
    LC4 --> WT3

    classDef core fill:#e3f2fd,stroke:#1565c0;
    classDef dsl fill:#e8f5e9,stroke:#2e7d32;
    classDef sigAlg fill:#f3e5f5,stroke:#7b1fa2;
    classDef widgets fill:#fff3e0,stroke:#e65100;
    classDef window fill:#fce4ec,stroke:#c2185b;
    classDef lcncInt fill:#e0f2f1,stroke:#00695c;

    class WT1,WT2,WT3,WT4 core;
    class D1,D2,D3,D4 dsl;
    class SA1,SA2,SA3 sigAlg;
    class W1,W2,W3,W4,W5 widgets;
    class W6,W7,W8,W9,W10 window;
    class LC1,LC2,LC3,LC4 lcncInt;
```

---

## 6. Windowing Abstraction → Forge User Interface

```mermaid
flowchart LR
    subgraph "Forge Core (libs/forge/)"
        FC1["ForgeWorkspace interface<br/>Files, Snapshots, Prompts, Workflows,<br/>Execution, Collaboration, Cascades, PatchBay"]
        FC2["ForgeWorkspaceImpl<br/>In-memory implementation"]
        FC3["ForgeTypes.kt<br/>PatchCable, ModuleSpec, PatchBay,<br/>WorkflowStep, ForgeWorkflow, AgentType"]
        FC4["KanbanTypes.kt<br/>KanbanBoard ↔ CascadeGraph ↔ Mermaid/DOT"]
        FC5["ForgeRunner.kt<br/>Execution engine"]
    end

    subgraph "Forge UI (libs/forge-ui/)"
        FU1["ForgeComponents.kt<br/>Compose Multiplatform UI components"]
        FU2["ConfixCodec.kt<br/>Confix ↔ UI serialization"]
        FU3["Main.kt (JVM/JS)<br/>Entry points"]
    end

    subgraph "Window-Toolkit Bridge"
        WB1["SwingWindowFactory → Compose<br/>Desktop/Web targets"]
        WB2["InputDispatcher → Compose gesture system"]
        WB3["RenderContext → Compose Canvas/GraphicsLayer"]
        WB4["Widget<T> → @Composable functions"]
    end

    subgraph "Signal Flow in Forge"
        SF1["ForgeWorkspace.events()<br/>Flow<CollaborationEvent>"]
        SF2["forge.execute()/executeCascade()<br/>Flow<StepProgress/CascadeProgress>"]
        SF3["streamPatchBay()<br/>Flow<Map<String,String>> real-time"]
        SF4["stream(id: ForgeFileId)<br/>ReceiveChannel<String> file streaming"]
    end

    subgraph "Cascade / Patch Bay Visualization"
        CP1["CascadeDetectionRequest → CascadeDetectionResult<br/>detectCascades()"]
        CP2["OperationalCascade ↔ PatchBay modules<br/>createModuleFromCascade()"]
        CP3["PatchBayGraph nodes/edges → Compose graph viz"]
        CP4["LayoutAlgorithm: FORCE_DIRECTED, HIERARCHICAL,<br/>CIRCULAR, GRID, ORTHOGONAL"]
    end

    subgraph "Kanban ↔ Forge Integration"
        KI1["KanbanBoard.toCascadeGraph()<br/>Columns→SOURCE/SINK, Cards→MAP/FILTER"]
        KI2["KanbanBoard.toPatchBayModules()<br/>WorkflowStep → ModuleSpec"]
        KI3["ForgeWorkflow.toPatchCables()/toPatchBayModules()<br/>Workflow → real-time signal routing"]
        KI4["Shape Dimensions (17) on workflow steps<br/>Annotated Mermaid diagrams"]
    end

    FC1 --> FC2
    FC1 --> FC3
    FC1 --> FC4
    FC1 --> FC5
    FC3 --> FU1
    FC4 --> FU1
    FC5 --> FU1
    FU1 --> FU2
    FU2 --> FU3
    WB1 --> FU1
    WB2 --> FU1
    WB3 --> FU1
    WB4 --> FU1
    FC1 --> SF1
    FC1 --> SF2
    FC1 --> SF3
    FC1 --> SF4
    FC1 --> CP1
    CP1 --> CP2
    CP2 --> CP3
    CP3 --> CP4
    FC4 --> KI1
    FC4 --> KI2
    FC3 --> KI3
    FC3 --> KI4

    classDef forge fill:#e3f2fd,stroke:#1565c0;
    classDef forgeUI fill:#e8f5e9,stroke:#2e7d32;
    classDef bridge fill:#fff3e0,stroke:#e65100;
    classDef signal fill:#fce4ec,stroke:#c2185b;
    classDef cascade fill:#f3e5f5,stroke:#7b1fa2;
    classDef kanbanInt fill:#e0f2f1,stroke:#00695c;

    class FC1,FC2,FC3,FC4,FC5 forge;
    class FU1,FU2,FU3 forgeUI;
    class WB1,WB2,WB3,WB4 bridge;
    class SF1,SF2,SF3,SF4 signal;
    class CP1,CP2,CP3,CP4 cascade;
    class KI1,KI2,KI3,KI4 kanbanInt;
```

---

## Complete End-to-End Flow

```mermaid
flowchart TD
    subgraph "1. Facetted Cursors"
        A1["RowVec = Series2<Any?, ColumnMeta↻><br/>Split series: values + lazy metadata"]
        A2["Cursor = Series<RowVec><br/>Indexed columnar storage"]
        A3["FacetedRow = Join<RowVec, Any><br/>Row with arbitrary facet payload"]
        A4["ConfixIndex = FacetedRow<Any><br/>Cursor with structural index"]
    end

    subgraph "2. Confix Parser (Gateway)"
        B1["Syntax.scan(): JSON/CBOR/YAML → Cursor"]
        B2["scan0(): flat spans → Join<Cursor, FlatIndex><br/>open/close/tag/depth/childOf"]
        B3["buildTree(): flat → nested RowVec hierarchy"]
        B4["ConfixDoc = Join<ConfixIndex, Series<Byte>><br/>Facade (immutable) + Body (swappable)"]
        B5["BlackBoardEntry: doc + role + provenance<br/>OBSERVATION│DERIVED│AGGREGATE│TYPEDEF_CHAIN"]
    end

    subgraph "3. User Signals Algebra"
        C1["Signal<T>: value + changes: Flow<T>"]
        C2["SignalSource<T>: emit()/emitSuspend()"]
        C3["0D: Toggle, IdiotLight, MomentaryButton, RadioToggle"]
        C4["1D: Slider, Knob, Dial, LevelMeter"]
        C5["TextField: Signal<TextFieldState>"]
        C6["Combinators: map, combine, filter, sample"]
    end

    subgraph "4. Kanban / Idea Grapher"
        D1["KanbanBoard: columns, cards, swimlanes"]
        D2["CascadeGraph: nodes (SOURCE/MAP/FILTER/REDUCE/JOIN/SINK) + edges"]
        D3["Mermaid/DOT rendering via toMermaid()/toDot()"]
        D4["PatchCableShapeDimension (17 dims):<br/>SIGNAL_FLOW, FEEDBACK, SPECTRAL, HYBRID..."]
        D5["WorkflowStep → ModuleSpec + PatchCable<br/>PortSpec: DATA/CONTROL/AUDIO/VIDEO/CV/GATE/POLY"]
    end

    subgraph "5. LCNC (Linear Confix/Columnar)"
        E1["LcncGrid: Cursor + Series<ConfixDoc>"]
        E2["projectColumns/addFormulaColumn/page()"]
        E3["LcncReduction<Key,Val,Acc,Out>:<br/>keyAlg + valueAlg + phaseAlg + carrierAlg"]
        E4["Pre-configured: forgeCascade, confixParse, crmsFold"]
        E5["KeyAlg/ValueAlg/PhaseAlg/CarrierAlg algebras"]
    end

    subgraph "6. Windowing Abstraction"
        F1["WindowShell + SignalFactory"]
        F2["SignalTemplateBuilder: beside/above/overlay/whenVisible"]
        F3["Widget<T>: state → RenderContext → Unit"]
        F4["PanelWidget + Layout(Column/Row/Grid/Flow/Stack)"]
        F5["Window/WindowFactory/SwingWindow + InputDispatcher"]
        F6["LCNC Grid → paged signals, ConfixDoc.body swap → emit()"]
    end

    subgraph "7. Forge User Interface"
        G1["ForgeWorkspace: Files, Snapshots, Prompts, Workflows,<br/>Execution, Collaboration, Cascades, PatchBay"]
        G2["ForgeWorkflow: steps (LLM/Code/Agent/File/Cond/Parallel/Cascade)"]
        G3["PatchBay: modules + cables + layers (SIGNAL/CONTROL/...)"]
        G4["CascadeDetection → OperationalCascade → PatchBay modules"]
        G5["KanbanBoard ↔ CascadeGraph ↔ PatchBay visualization"]
        G6["Compose Multiplatform (forge-ui): ForgeComponents + ConfixCodec"]
        G7["Real-time: Flow<Event>, Flow<Progress>, streamPatchBay()"]
    end

    A1 --> A2 --> A3 --> A4
    A4 --> B1 --> B2 --> B3 --> B4 --> B5
    B4 --> C1
    B5 --> C1
    C1 --> C2
    C2 --> C3
    C2 --> C4
    C2 --> C5
    C2 --> C6
    C3 --> D1
    C4 --> D1
    C5 --> D1
    D1 --> D2
    D2 --> D3
    D1 --> D4
    D4 --> D5
    D1 --> E1
    D2 --> E1
    E1 --> E2
    E2 --> E3
    E3 --> E4
    E3 --> E5
    E1 --> F1
    E3 --> F1
    F1 --> F2
    F2 --> F3
    F3 --> F4
    F4 --> F5
    E1 --> F6
    B4 --> F6
    F1 --> G1
    F6 --> G1
    D1 --> G1
    D5 --> G1
    E4 --> G1
    G1 --> G2
    G1 --> G3
    G2 --> G4
    G3 --> G4
    D1 --> G5
    D2 --> G5
    G3 --> G5
    G1 --> G6
    G3 --> G6
    G1 --> G7
    G3 --> G7

    classDef stage1 fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px;
    classDef stage2 fill:#e3f2fd,stroke:#1565c0,stroke-width:2px;
    classDef stage3 fill:#fce4ec,stroke:#c2185b,stroke-width:2px;
    classDef stage4 fill:#fff3e0,stroke:#e65100,stroke-width:2px;
    classDef stage5 fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px;
    classDef stage6 fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px;
    classDef stage7 fill:#e0f2f1,stroke:#00695c,stroke-width:2px;

    class A1,A2,A3,A4 stage1;
    class B1,B2,B3,B4,B5 stage2;
    class C1,C2,C3,C4,C5,C6 stage3;
    class D1,D2,D3,D4,D5 stage4;
    class E1,E2,E3,E4,E5 stage5;
    class F1,F2,F3,F4,F5,F6 stage6;
    class G1,G2,G3,G4,G5,G6,G7 stage7;
```

---

## Key Data Flow Properties

| Layer | Core Type | Key Property |
|-------|-----------|--------------|
| **Facetted Cursors** | `RowVec = Series2<Any?, ColumnMeta↻>` | Split series: values + lazy metadata supplier |
| **Confix** | `ConfixDoc = Join<ConfixIndex, Series<Byte>>` | Facade (immutable index) + Body (zero-copy reload) |
| **User Signals** | `Signal<T> = { value: T, changes: Flow<T> }` | Composable algebra: map/combine/filter/sample |
| **Kanban/Idea Grapher** | `KanbanBoard → CascadeGraph` | Visualizable as Mermaid/DOT + PatchCable dimensions |
| **LCNC** | `LcncReduction<Key,Val,Acc,Out>` | Four-algebra map-reduce: key/value/phase/carrier |
| **Windowing** | `Widget<T>: State → RenderContext → Unit` | Signal-driven, platform-agnostic composition |
| **Forge UI** | `ForgeWorkspace + PatchBay` | Real-time collaboration, workflow execution, signal routing |

---

## Architecture Principles

1. **Confix as Cursor Gateway** — All structured tree parsing (JSON/CBOR/YAML) produces a `Cursor = Series<RowVec>` with `ConfixIndex` facade for navigation
2. **Zero-Copy Reload** — `ConfixDoc.body` swap preserves facade; triggers signal `emit()` for reactive UI
3. **Signal Algebra as Glue** — Every layer exposes `Signal<T>`; combinators enable cross-layer composition
4. **Map-Reduce Uniformity** — LCNC reductions unify Forge cascades, Confix parsing, CRMS folding under same algebra
5. **Patch Cable as Universal Routing** — Workflow steps ↔ Modules; Kanban cards ↔ Nodes; Signals ↔ Ports
6. **Platform-Agnostic Windowing** — `WindowToolkitDsl` builds signal trees; `SwingWindowFactory`/`Compose` are pluggable renderers
7. **Forge as Capstone** — Integrates all layers: files→ConfixDoc, workflows→PatchBay, cascades→LCNC reductions, UI→Signal-driven Compose