# TrikeShed Architecture - Mermaid Diagrams (Renderable)

---

## Complete End-to-End Flow

```mermaid
flowchart TD
    subgraph "1. Facetted Cursors"
        A1["RowVec = Series2<Any?, ColumnMeta↻><br/>Split series: values + lazy metadata supplier"]
        A2["Cursor = Series<RowVec><br/>Indexed columnar storage"]
        A3["FacetedRow = Join<RowVec, Any><br/>Row with arbitrary facet payload"]
        A4["ConfixIndex = FacetedRow<Any><br/>Cursor with structural index"]
    end

    subgraph "2. Confix ParserGateway"
        B1["Syntax.scan(): JSON/CBOR/YAML → Cursor"]
        B2["scan0(): flat spans → Join<Cursor, FlatIndex><br/>open/close/tag/depth/childOf"]
        B3["buildTree(): flat → nested RowVec hierarchy"]
        B4["ConfixDoc = Join<ConfixIndex, Series<Byte>><br/>Facade immutable + Body swappable"]
        B5["BlackBoardEntry: doc + role + provenance<br/>OBSERVATION/DERIVED/AGGREGATE/TYPEDEF_CHAIN"]
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
        D2["CascadeGraph: nodes SOURCE/MAP/FILTER/REDUCE/JOIN/SINK + edges"]
        D3["Mermaid/DOT rendering via toMermaid()/toDot()"]
        D4["PatchCableShapeDimension 17 dims:<br/>SIGNAL_FLOW, FEEDBACK, SPECTRAL, HYBRID..."]
        D5["WorkflowStep → ModuleSpec + PatchCable<br/>PortSpec: DATA/CONTROL/AUDIO/VIDEO/CV/GATE/POLY"]
    end

    subgraph "5. LCNC Linear Confix"
        E1["LcncGrid: Cursor + Series<ConfixDoc>"]
        E2["projectColumns/addFormulaColumn/page()"]
        E3["LcncReduction<Key,Val,Acc,Out>:<br/>keyAlg + valueAlg + phaseAlg + carrierAlg"]
        E4["Pre-configured: forgeCascade, confixParse, crmsFold"]
        E5["KeyAlg/ValueAlg/PhaseAlg/CarrierAlg algebras"]
    end

    subgraph "6. Windowing Abstraction"
        F1["WindowShell + SignalFactory"]
        F2["SignalTemplateBuilder: beside/above/overlay/whenVisible"]
        F3["Widget<T>: State → RenderContext → Unit"]
        F4["PanelWidget + Layout(Column/Row/Grid/Flow/Stack)"]
        F5["Window/WindowFactory/SwingWindow + InputDispatcher"]
        F6["LCNC Grid → paged signals, ConfixDoc.body swap → emit()"]
    end

    subgraph "7. Forge User Interface"
        G1["ForgeWorkspace: Files, Snapshots, Prompts, Workflows,<br/>Execution, Collaboration, Cascades, PatchBay"]
        G2["ForgeWorkflow: steps LLM/Code/Agent/File/Cond/Parallel/Cascade"]
        G3["PatchBay: modules + cables + layers SIGNAL/CONTROL/..."]
        G4["CascadeDetection → OperationalCascade → PatchBay modules"]
        G5["KanbanBoard ↔ CascadeGraph ↔ PatchBay visualization"]
        G6["Compose Multiplatform: ForgeComponents + ConfixCodec"]
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

    classDef s1 fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px;
    classDef s2 fill:#e3f2fd,stroke:#1565c0,stroke-width:2px;
    classDef s3 fill:#fce4ec,stroke:#c2185b,stroke-width:2px;
    classDef s4 fill:#fff3e0,stroke:#e65100,stroke-width:2px;
    classDef s5 fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px;
    classDef s6 fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px;
    classDef s7 fill:#e0f2f1,stroke:#00695c,stroke-width:2px;

    class A1,A2,A3,A4 s1;
    class B1,B2,B3,B4,B5 s2;
    class C1,C2,C3,C4,C5,C6 s3;
    class D1,D2,D3,D4,D5 s4;
    class E1,E2,E3,E4,E5 s5;
    class F1,F2,F3,F4,F5,F6 s6;
    class G1,G2,G3,G4,G5,G6,G7 s7;
```

---

## Confix Parser Detail

```mermaid
flowchart LR
    subgraph "Syntax Entry"
        S1["Syntax.JSON/CBOR/YAML"]
        S2["scan(bytes) → Cursor"]
        S3["scan0(src) → Join<Cursor, FlatIndex>"]
    end

    subgraph "Flat Index"
        F1["spans: Series<Twin<Int>><br/>open, close"]
        F2["tags: Series<IOMemento><br/>IoObject/IoArray/IoString/..."]
        F3["depths: Series<Int>"]
        F4["childOf: (Int) → Series<Int>"]
    end

    subgraph "Tree Building"
        T1["buildTree(sOpen, sClose, sTag)"]
        T2["Stack-based parsing"]
        T3["Nested RowVec hierarchy"]
    end

    subgraph "ConfixDoc"
        D1["ConfixDoc = Join<ConfixIndex, Series<Byte>>"]
        D2["index.facade: ConfixIndex (immutable)"]
        D3["body: Series<Byte> (swappable)"]
        D4["roots: Cursor = index[TreeCursor]"]
        D5["root: RowVec?"]
    end

    subgraph "Navigation"
        N1["ConfixCell = Join<RowVec, Series<Byte>>"]
        N2["ConfixCell.get(key:String) → ConfixCell?"]
        N3["ConfixCell.get(idx:Int) → ConfixCell?"]
        N4["ConfixDoc.getAt(path) → RowVec?"]
        N5["RowVec.reify(src) → Any?"]
        N6["JsPath = Series<Join<String,Int>>"]
    end

    subgraph "BlackBoard"
        B1["BlackBoardEntry: doc + role + timestamp + provenance"]
        B2["ConfixRole: OBSERVATION/DERIVED/AGGREGATE/TYPEDEF_CHAIN/POINTCUT_STATS"]
    end

    S1 --> S2
    S2 --> S3
    S3 --> F1
    S3 --> F2
    S3 --> F3
    S3 --> F4
    F1 --> T1
    F2 --> T1
    F3 --> T1
    F4 --> T1
    T1 --> T2
    T2 --> T3
    T3 --> D1
    D1 --> D2
    D1 --> D3
    D2 --> D4
    D4 --> D5
    D1 --> N1
    N1 --> N2
    N1 --> N3
    N2 --> N4
    N3 --> N4
    N1 --> N5
    N4 --> N6
    D1 --> B1
    B1 --> B2

    classDef entry fill:#e3f2fd,stroke:#1565c0;
    classDef flat fill:#e8f5e9,stroke:#2e7d32;
    classDef tree fill:#fff3e0,stroke:#e65100;
    classDef doc fill:#fce4ec,stroke:#c2185b;
    classDef nav fill:#f3e5f5,stroke:#7b1fa2;
    classDef bb fill:#e0f2f1,stroke:#00695c;

    class S1,S2,S3 entry;
    class F1,F2,F3,F4 flat;
    class T1,T2,T3 tree;
    class D1,D2,D3,D4,D5 doc;
    class N1,N2,N3,N4,N5,N6 nav;
    class B1,B2 bb;
```

---

## User Signals Algebra Detail

```mermaid
flowchart TB
    subgraph "Base Algebra"
        B1["Signal<T><br/>value: T<br/>changes: Flow<T>"]
        B2["map(transform) → Signal<R>"]
        B3["combine(other, combiner) → Signal<R>"]
        B4["filter(predicate) → Signal<T>"]
        B5["sample(interval) → Signal<T>"]
    end

    subgraph "Source Interface"
        SRC["SignalSource<T> : Signal<T><br/>emit(value): T<br/>emitSuspend(value): T"]
    end

    subgraph "Combinators (Internal)"
        COM1["MappedSignal<T,R>"]
        COM2["CombinedSignal<T,U,R>"]
        COM3["FilteredSignal<T>"]
        COM4["SampledSignal<T>"]
    end

    subgraph "0-Dimensional Signals"
        D01["Toggle: Signal<Boolean><br/>isOn, toggle(), turnOn(), turnOff()"]
        D02["IdiotLight: Signal<Boolean><br/>isLit, flash(), pulse()"]
        D03["MomentaryButton: Signal<Boolean><br/>isPressed, press(), release(), tap()"]
        D04["RadioToggle<T>: Signal<T><br/>selected, options[], select(), clear()"]
    end

    subgraph "1-Dimensional Signals"
        D11["Slider: Signal<Double><br/>min/max/step, normalized, setValue()"]
        D12["Knob: Signal<Double><br/>detents, radians, rotateBy(), snapToDetent()"]
        D13["Dial<T>: Signal<T><br/>positions[], index, next(), prev(), goto()"]
        D14["LevelMeter: Signal<Double><br/>level, peak, peakHoldMillis, setLevel()"]
    end

    subgraph "Text Signal"
        TXT["TextField: Signal<TextFieldState><br/>text, caret, selection, focused, committed<br/>insert/backspace/delete/moveCaret/setSelection/clear/commit"]
    end

    subgraph "Factory Functions"
        FAC["toggle() idiotLight() momentaryButton()<br/>radioToggle() slider() knob()<br/>dial() levelMeter() textField()"]
    end

    B1 --> SRC
    B1 --> B2
    B1 --> B3
    B1 --> B4
    B1 --> B5
    B2 --> COM1
    B3 --> COM2
    B4 --> COM3
    B5 --> COM4
    SRC --> D01
    SRC --> D02
    SRC --> D03
    SRC --> D04
    SRC --> D11
    SRC --> D12
    SRC --> D13
    SRC --> D14
    SRC --> TXT
    D01 --> FAC
    D02 --> FAC
    D03 --> FAC
    D04 --> FAC
    D11 --> FAC
    D12 --> FAC
    D13 --> FAC
    D14 --> FAC
    TXT --> FAC

    classDef base fill:#fce4ec,stroke:#c2185b;
    classDef comb fill:#f3e5f5,stroke:#7b1fa2;
    classDef d0 fill:#e3f2fd,stroke:#1565c0;
    classDef d1 fill:#e8f5e9,stroke:#2e7d32;
    classDef txt fill:#fff3e0,stroke:#e65100;
    classDef fac fill:#e0f2f1,stroke:#00695c;

    class B1,B2,B3,B4,B5 base;
    class SRC base;
    class COM1,COM2,COM3,COM4 comb;
    class D01,D02,D03,D04 d0;
    class D11,D12,D13,D14 d1;
    class TXT txt;
    class FAC fac;
```

---

## Kanban → CascadeGraph → Patch Bay

```mermaid
flowchart LR
    subgraph "Kanban Types"
        K1["KanbanBoard<br/>id, name, columns[], cards[], swimlanes[]"]
        K2["KanbanColumn<br/>id, name, order, wipLimit"]
        K3["KanbanCard<br/>id, title, desc, columnId, order,<br/>assignee, priority, dependencies[], tags"]
        K4["Swimlane<br/>id, name, color, cardIds[]"]
        K5["CardPriority: LOW/MEDIUM/HIGH/CRITICAL"]
    end

    subgraph "CascadeGraph"
        CG1["CascadeGraph<br/>nodes: CascadeNode[], edges: CascadeEdge[]"]
        CG2["CascadeNode<br/>id, type: SOURCE/MAP/FILTER/REDUCE/JOIN/SINK<br/>label, config: Map<String,String>"]
        CG3["CascadeEdge<br/>from, to, dataFlow: String"]
    end

    subgraph "Rendering"
        R1["toMermaid(): graph LR<br/>SOURCE=[label], SINK=]label[, else=(label)"]
        R2["toDot(): digraph<br/>SOURCE=doubleoctagon, SINK=doublecircle<br/>priority colors: CRITICAL=red, HIGH=orange"]
    end

    subgraph "Shape Dimensions (17)"
        SD["PatchCableShapeDimension enum:<br/>SIGNAL_FLOW, CONTROL_FLOW, STATE_MUTATION,<br/>PARALLELISM, EXTERNAL_IO, TEMPORAL, STOCHASTIC,<br/>COMPOSITE, FEEDBACK, MODULATION, QUANTIZATION,<br/>SPATIAL, SPECTRAL, ENVELOPE, SEQUENCE,<br/>POLYPHONIC, GRANULAR, HYBRID"]
    end

    subgraph "Workflow → Patch Bay"
        WB1["WorkflowStep.getPatchCableShapeDimensions()<br/>Maps step type → Set<Dimension>"]
        WB2["ModuleSpec: id, moduleType, inputPorts[],<br/>outputPorts[], parameterPorts[], position"]
        WB3["ModuleType (22): LLM_CALL, CODE_EXECUTION,<br/>AGENT_INVOCATION, CASCADE_EXECUTION,<br/>MAP, REDUCE, FILTER, PROJECT, JOIN,<br/>OSCILLATOR, NOISE, SEQUENCER,<br/>GATE, TRIGGER, COMPARATOR, LOGIC,<br/>MIXER, SPLITTER, MERGER, SWITCH, MULTIPLEXER"]
        WB4["PatchCable: source→dest PortAddress,<br/>transform, state, routing, latency"]
        WB5["PatchBay: modules + cables + layers<br/>SIGNAL/CONTROL/METADATA/VISUALIZATION/DEBUG"]
    end

    K1 --> K2
    K1 --> K3
    K1 --> K4
    K1 --> K5
    K1 --> CG1
    CG1 --> CG2
    CG1 --> CG3
    CG1 --> R1
    CG1 --> R2
    K3 --> SD
    SD --> WB1
    WB1 --> WB2
    WB2 --> WB3
    WB4 --> WB5
    WB2 --> WB5

    classDef kanban fill:#e3f2fd,stroke:#1565c0;
    classDef cascade fill:#fff3e0,stroke:#e65100;
    classDef render fill:#e8f5e9,stroke:#2e7d32;
    classDef dim fill:#f3e5f5,stroke:#7b1fa2;
    classDef patch fill:#fce4ec,stroke:#c2185b;

    class K1,K2,K3,K4,K5 kanban;
    class CG1,CG2,CG3 cascade;
    class R1,R2 render;
    class SD dim;
    class WB1,WB2,WB3,WB4,WB5 patch;
```

---

## LCNC Reduction Algebra

```mermaid
flowchart TB
    subgraph "LcncGrid"
        G1["LcncGrid(cursor: Cursor, srcDocs: Series<ConfixDoc>)"]
        G2["rowCount = cursor.size"]
        G3["projectColumns(indices: IntArray): LcncGrid"]
        G4["addFormulaColumn(formula): LcncGrid"]
        G5["page(start, end): LcncGrid"]
    end

    subgraph "LcncReduction<Key, Val, Acc, Out>"
        R1["KeyAlg<Key>: extractor, hierarchy, order"]
        R2["ValueAlg<Val, Acc>: folder, merger, initial"]
        R3["PhaseAlg: mapPhase → reducePhase → rereducePhase → formatOutput"]
        R4["CarrierAlg: carrier: Any → ReductionCarrier"]
    end

    subgraph "Pre-configured Reductions"
        P1["forgeCascade(keyHierarchy, metrics)<br/>Key=List<String>, Val=Map, Acc=MultiMetricAccumulator,<br/>Out=List<CascadeOutputRow>"]
        P2["confixParse()<br/>Key=ConfixStructuralKey, Val=Byte, Acc=TreeBuilderState,<br/>Out=Cursor"]
        P3["crmsFold()<br/>Key=Int(callsiteHash), Val=TraceEvent, Acc=ConflictCell,<br/>Out=List<ConflictCell>"]
    end

    subgraph "Supporting Algebras"
        S1["LcncKeyAlg: confixStructuralKey(), naturalKeyOrder()"]
        S2["LcncValueAlg: forgeMultiMetricReducer(), forgeMerger(),<br/>crmsPairAndEigsort(), crmsMerger()"]
        S3["LcncPhaseAlg: forgePhaseAlg, confixPhaseAlg, crmsPhaseAlg"]
        S4["LcncCarrierAlg: seriesCarrierAlg, arrayCarrier, ringCarrier"]
    end

    G1 --> R1
    G1 --> R2
    G1 --> R3
    G1 --> R4
    G2 --> R1
    G3 --> R1
    G4 --> R1
    G5 --> R1
    R1 --> P1
    R1 --> P2
    R1 --> P3
    R2 --> P1
    R2 --> P2
    R2 --> P3
    R3 --> P1
    R3 --> P2
    R3 --> P3
    R4 --> P1
    R4 --> P2
    R4 --> P3
    P1 --> S1
    P1 --> S2
    P1 --> S3
    P1 --> S4
    P2 --> S1
    P2 --> S3
    P2 --> S4
    P3 --> S1
    P3 --> S2
    P3 --> S3
    P3 --> S4

    classDef grid fill:#e3f2fd,stroke:#1565c0;
    classDef red fill:#e8f5e9,stroke:#2e7d32;
    classDef pre fill:#fff3e0,stroke:#e65100;
    classDef supp fill:#f3e5f5,stroke:#7b1fa2;

    class G1,G2,G3,G4,G5 grid;
    class R1,R2,R3,R4 red;
    class P1,P2,P3 pre;
    class S1,S2,S3,S4 supp;
```

---

## Window Toolkit → Forge UI

```mermaid
flowchart LR
    subgraph "Window Toolkit Core"
        W1["WindowContextElement<br/>signalContextInstance + open/close"]
        W2["SignalContextElement<br/>SignalFactory + signal algebra"]
        W3["SignalFactory: toggle, light, button, radio,<br/>slider, knob, dial, level, textField"]
        W4["WindowShell: components + signals factory"]
    end

    subgraph "DSL"
        D1["windowContext(block) → WindowShell"]
        D2["panel(block) → SignalTemplate"]
        D3["SignalTemplateBuilder:<br/>template, beside, above, overlay, whenVisible"]
        D4["RenderPipeline / ConsoleRenderer:<br/>pipeline(), live(), quickTui()"]
    end

    subgraph "Widgets"
        WD1["Widget<T>: State → RenderContext → Unit"]
        WD2["PanelWidget: children[], Layout, padding"]
        WD3["Layout: Column/Row/Grid/Flow/Stack"]
        WD4["ToggleWidget, SliderWidget, KnobWidget,<br/>RadioWidget, LevelMeterWidget, TextFieldWidget"]
    end

    subgraph "Platform Windows"
        PW1["Window: title, size, position, open/close/resize/move"]
        PW2["WindowFactory: create(), createChild()"]
        PW3["SwingWindowFactory / SwingWindow"]
        PW4["InputDispatcher: Mouse/Key/Scroll → handlers"]
        PW5["RenderContext: drawRect, drawText, drawLine, drawCircle"]
    end

    subgraph "LCNC Integration"
        LI1["LCNC Grid → paged signals via cursor.page()"]
        LI2["LCNC reductions → Signal.map/combine transforms"]
        LI3["ConfixDoc.body swap → Signal.emit() zero-copy reload"]
        LI4["ConfixRole → signal metadata (OBSERVATION/DERIVED/...)"]
    end

    subgraph "Forge Core"
        FC1["ForgeWorkspace interface:<br/>Files, Snapshots, Prompts, Workflows, Execution,<br/>Collaboration, Cascades, PatchBay"]
        FC2["ForgeTypes: PatchCable, ModuleSpec, PatchBay,<br/>WorkflowStep, ForgeWorkflow, AgentType"]
        FC3["KanbanTypes: KanbanBoard ↔ CascadeGraph"]
    end

    subgraph "Forge UI (Compose)"
        FU1["ForgeComponents.kt: Compose UI components"]
        FU2["ConfixCodec.kt: Confix ↔ UI serialization"]
        FU3["Main.kt (JVM/JS): Entry points"]
    end

    subgraph "Bridge"
        BR1["SwingWindowFactory → Compose Desktop/Web"]
        BR2["InputDispatcher → Compose gesture system"]
        BR3["RenderContext → Compose Canvas/GraphicsLayer"]
        BR4["Widget<T> → @Composable functions"]
    end

    subgraph "Signal Flow in Forge"
        SF1["workspace.events(): Flow<CollaborationEvent>"]
        SF2["execute(): Flow<StepProgress>"]
        SF3["executeCascade(): Flow<CascadeProgress>"]
        SF4["streamPatchBay(): Flow<Map<String,String>>"]
        SF5["stream(fileId): ReceiveChannel<String>"]
    end

    W1 --> W2
    W2 --> W3
    W3 --> W4
    W4 --> D1
    D1 --> D2
    D2 --> D3
    D3 --> D4
    D4 --> WD1
    WD1 --> WD2
    WD2 --> WD3
    WD1 --> WD4
    WD4 --> PW1
    PW1 --> PW2
    PW2 --> PW3
    PW3 --> PW4
    PW4 --> PW5
    LI1 --> W4
    LI2 --> W4
    LI3 --> W2
    LI4 --> W3
    W4 --> FC1
    LI1 --> FC1
    LI3 --> FC1
    FC1 --> FC2
    FC1 --> FC3
    FC2 --> FU1
    FC3 --> FU1
    FU1 --> FU2
    FU2 --> FU3
    BR1 --> FU1
    BR2 --> FU1
    BR3 --> FU1
    BR4 --> FU1
    FC1 --> SF1
    FC1 --> SF2
    FC1 --> SF3
    FC1 --> SF4
    FC1 --> SF5
    FC2 --> SF1

    classDef core fill:#e3f2fd,stroke:#1565c0;
    classDef dsl fill:#e8f5e9,stroke:#2e7d32;
    classDef wdg fill:#fff3e0,stroke:#e65100;
    classDef plat fill:#fce4ec,stroke:#c2185b;
    classDef lcnc fill:#f3e5f5,stroke:#7b1fa2;
    classDef forge fill:#e0f2f1,stroke:#00695c;
    classDef fui fill:#f3e5f5,stroke:#7b1fa2;
    classDef br fill:#fff8e1,stroke:#f57f17;
    classDef sig fill:#e8f5e9,stroke:#2e7d32;

    class W1,W2,W3,W4 core;
    class D1,D2,D3,D4 dsl;
    class WD1,WD2,WD3,WD4 wdg;
    class PW1,PW2,PW3,PW4,PW5 plat;
    class LI1,LI2,LI3,LI4 lcnc;
    class FC1,FC2,FC3 forge;
    class FU1,FU2,FU3 fui;
    class BR1,BR2,BR3,BR4 br;
    class SF1,SF2,SF3,SF4,SF5 sig;
```