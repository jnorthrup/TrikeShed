/**
 * TrikeShed Blockly — Custom ConnectionChecker
 *
 * Implements the kernel algebra type system for mated surfaces.
 *
 * Blockly ConnectionType values (from live instance):
 *   INPUT_VALUE = 1, OUTPUT_VALUE = 2, NEXT_STATEMENT = 3, PREVIOUS_STATEMENT = 4
 *
 * The checker has 3 phases:
 *   1. doSafetyChecks  — prevents self-connect, recursive loops
 *   2. doTypeChecks    — THE MATED SURFACE LOGIC: checks if check_ arrays overlap
 *   3. doDragChecks    — prevents dragging blocks into themselves
 *
 * Our type hierarchy maps TrikeShed kernel types onto connection check strings.
 * Type compatibility follows the algebra's subtyping lattice:
 *
 *   Signal <: Signal          (covariant)
 *   Toggle/Slider/etc <: Signal  (all signals are Signals)
 *   SignalComponent <: SignalComponent
 *   Cursor <: Series          (a cursor is a Series of RowVec)
 *   RowVec <: Series          (RowVec is Series2)
 *   ConfixDoc <: Join         (ConfixDoc = Join<ConfixIndex, Series<Byte>>)
 *   ReductionResult <: Cursor (reduction produces a cursor)
 *   TreeCursor <: Cursor
 *   CascadeGraph <: Cursor    (cascade graph IS a cursor DAG)
 */

// === TYPE HIERARCHY LATTICE ===
// Maps each output type to the set of types it's compatible with as input.
// Key insight: if output is "Toggle", it can plug into inputs accepting
// "Toggle", "Signal", or null/any.
const TYPE_LATTICE = {
    // user-signals hierarchy
    'Toggle':          ['Toggle', 'Signal'],
    'IdiotLight':      ['IdiotLight', 'Signal'],
    'MomentaryButton': ['MomentaryButton', 'Signal'],
    'Slider':          ['Slider', 'Signal'],
    'Knob':            ['Knob', 'Signal'],
    'Dial':            ['Dial', 'Signal'],
    'Signal':          ['Signal'],

    // component hierarchy
    'SignalComponent': ['SignalComponent'],

    // kernel algebra hierarchy
    'Join':    ['Join'],
    'Series':  ['Series'],
    'Twin':    ['Twin', 'Join'],       // Twin<T> = Join<T,T>
    'Cursor':  ['Cursor', 'Series'],   // Cursor = Series<RowVec>
    'RowVec':  ['RowVec', 'Series'],

    // lcnc hierarchy
    'ReductionResult': ['ReductionResult', 'Cursor'],

    // forge hierarchy
    'ForgeFile':     ['ForgeFile'],
    'ForgePrompt':   ['ForgePrompt'],
    'ForgeWorkflow': ['ForgeWorkflow'],
    'StepResult':    ['StepResult'],
    'ExecutionResult': ['ExecutionResult'],
    'ForgeArtifact': ['ForgeArtifact'],
    'Snapshot':      ['Snapshot'],

    // kanban hierarchy
    'KanbanBoard': ['KanbanBoard'],
    'KanbanCard':  ['KanbanCard'],
    'Column':      ['Column'],
    'Swimlane':    ['Swimlane'],
    'CascadeGraph': ['CascadeGraph', 'Cursor'],

    // confix hierarchy
    'ConfixDoc':   ['ConfixDoc', 'Join'],  // ConfixDoc = Join<ConfixIndex, Series<Byte>>
    'ConfixIndex': ['ConfixIndex'],
    'ScanToken':   ['ScanToken'],
    'TreeCursor':  ['TreeCursor', 'Cursor'],
};

/**
 * Check if an output type can mate with an input check type.
 * @param {string} outputType — the block's output type string
 * @param {string|null} inputCheck — the input's check type (or array)
 * @returns {boolean}
 */
function canTypeMate(outputType, inputCheck) {
    if (!inputCheck || (Array.isArray(inputCheck) && inputCheck.length === 0)) {
        return true; // null/empty check = accepts anything
    }
    const compatible = TYPE_LATTICE[outputType] || [outputType];
    const checks = Array.isArray(inputCheck) ? inputCheck : [inputCheck];
    return checks.some(check => compatible.includes(check));
}

/**
 * Custom ConnectionChecker for TrikeShed.
 * Extends the default checker to implement our type lattice.
 */
class TrikeShedConnectionChecker extends Blockly.ConnectionChecker {
    /**
     * Override doTypeChecks to use our kernel algebra lattice.
     * The default Blockly checker just checks array intersection.
     * Ours adds subtype relationships (Toggle is-a Signal, Cursor is-a Series, etc).
     */
    doTypeChecks(a, b) {
        // Determine which is input and which is output
        // INPUT_VALUE (1) accepts OUTPUT_VALUE (2)
        // PREVIOUS_STATEMENT (4) accepts NEXT_STATEMENT (3)
        let inputConn, outputConn;
        if (a.type === Blockly.ConnectionType.INPUT_VALUE ||
            a.type === Blockly.ConnectionType.PREVIOUS_STATEMENT) {
            inputConn = a;
            outputConn = b;
        } else {
            inputConn = b;
            outputConn = a;
        }

        const outputChecks = outputConn.getCheck();
        const inputChecks = inputConn.getCheck();

        // No checks on either side = compatible (Blockly default behavior)
        if (!outputChecks && !inputChecks) return true;
        if (!outputChecks) return true;  // output accepts anything
        if (!inputChecks) return true;   // input accepts anything

        // Apply our type lattice
        for (const outType of outputChecks) {
            for (const inType of inputChecks) {
                // Direct match
                if (outType === inType) return true;
                // Lattice match (subtype)
                if (canTypeMate(outType, inType)) return true;
                // Reverse lattice (for statement chains)
                if (canTypeMate(inType, outType)) return true;
            }
        }
        return false;
    }
}

// === MATED SURFACE INSPECTOR ===
// Draws colored connector lines between connected blocks on the canvas.
// Each connection type gets a distinct color and curve style.

const SURFACE_COLORS = {
    // Value connections (horizontal, output → input)
    'Signal':          '#89b4fa',  // blue
    'SignalComponent': '#b4befe',  // light blue
    'Toggle':          '#cba6f7',  // purple
    'Slider':          '#f9e2af',  // yellow
    'Knob':            '#f9e2af',
    'Dial':            '#f9e2af',
    'IdiotLight':      '#f38ba8',  // red
    'MomentaryButton': '#f38ba8',

    // Data flow connections
    'Cursor':          '#a6e3a1',  // green
    'Series':          '#a6e3a1',
    'ConfixDoc':       '#94e2d5',  // teal
    'ConfixIndex':     '#94e2d5',
    'ScanToken':       '#89dceb',  // cyan
    'TreeCursor':      '#89dceb',
    'ReductionResult': '#a6e3a1',

    // Forge pipeline
    'ForgeFile':       '#fab387',  // peach
    'ForgePrompt':     '#fab387',
    'ForgeWorkflow':   '#fab387',
    'StepResult':      '#f9e2af',
    'ExecutionResult': '#fab387',

    // Kanban
    'KanbanBoard':     '#cba6f7',  // purple
    'KanbanCard':      '#f5c2e7',  // pink
    'CascadeGraph':    '#cba6f7',

    // Kernel
    'Join':            '#94e2d5',
    'Twin':            '#94e2d5',
    'RowVec':          '#a6e3a1',
};

function getConnectionColor(check) {
    if (!check) return '#6c7086'; // gray for untyped
    const types = Array.isArray(check) ? check : [check];
    for (const t of types) {
        if (SURFACE_COLORS[t]) return SURFACE_COLORS[t];
    }
    return '#6c7086';
}

/**
 * Draw connector line overlay on the workspace SVG.
 * Called after each workspace render.
 */
function drawConnectorLines(workspace) {
    const svg = workspace.getCanvas();
    // Remove old overlay
    const old = svg.querySelector('#ts-connector-overlay');
    if (old) old.remove();

    const overlay = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    overlay.setAttribute('id', 'ts-connector-overlay');
    overlay.setAttribute('pointer-events', 'none');

    const allBlocks = workspace.getAllBlocks(false);
    allBlocks.forEach(block => {
        // Draw value connections (output → parent input)
        block.inputList.forEach(input => {
            if (input.connection && input.connection.targetConnection) {
                const targetBlock = input.connection.targetBlock();
                if (targetBlock) {
                    drawConnectionCurve(overlay, block, input, targetBlock, workspace);
                }
            }
        });

        // Draw statement connections (next → child previous)
        if (block.nextConnection && block.nextConnection.targetConnection) {
            const childBlock = block.nextConnection.targetBlock();
            if (childBlock) {
                drawStatementLine(overlay, block, childBlock, workspace);
            }
        }
    });

    svg.appendChild(overlay);
}

function drawConnectionCurve(overlay, parentBlock, input, childBlock, workspace) {
    // Get pixel positions
    const parentXY = parentBlock.getRelativeToSurfaceXY();
    const childXY = childBlock.getRelativeToSurfaceXY();

    // Get the input socket position on the parent
    const inputIdx = parentBlock.inputList.indexOf(input);
    const socketY = parentXY.y + (inputIdx + 0.5) * 24;

    // Get the output plug position on the child
    const plugY = childXY.y + childBlock.height / 2;

    const x1 = parentXY.x - 8; // left edge of parent input
    const x2 = childXY.x + childBlock.width; // right edge of child output
    const y1 = socketY;
    const y2 = plugY;

    // Bezier curve
    const cx1 = x1 - 20;
    const cx2 = x2 + 20;
    const path = `M ${x1} ${y1} C ${cx1} ${y1}, ${cx2} ${y2}, ${x2} ${y2}`;

    const check = input.connection.check_;
    const color = getConnectionColor(check);

    const pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    pathEl.setAttribute('d', path);
    pathEl.setAttribute('fill', 'none');
    pathEl.setAttribute('stroke', color);
    pathEl.setAttribute('stroke-width', '2');
    pathEl.setAttribute('stroke-dasharray', '4 2');
    pathEl.setAttribute('opacity', '0.6');
    overlay.appendChild(pathEl);

    // Draw type label at midpoint
    const midX = (x1 + x2) / 2;
    const midY = (y1 + y2) / 2;
    if (check && check.length > 0) {
        const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
        label.setAttribute('x', midX);
        label.setAttribute('y', midY - 4);
        label.setAttribute('text-anchor', 'middle');
        label.setAttribute('font-size', '9');
        label.setAttribute('font-family', 'monospace');
        label.setAttribute('fill', color);
        label.setAttribute('opacity', '0.8');
        label.textContent = Array.isArray(check) ? check[0] : check;
        overlay.appendChild(label);
    }
}

function drawStatementLine(overlay, parentBlock, childBlock, workspace) {
    const parentXY = parentBlock.getRelativeToSurfaceXY();
    const childXY = childBlock.getRelativeToSurfaceXY();

    // Statement connections are vertical chains — draw a vertical accent line
    const x = parentXY.x + 4;
    const y1 = parentXY.y + parentBlock.height - 2;
    const y2 = childXY.y + 2;

    const parentCheck = parentBlock.nextConnection.check_;
    const color = getConnectionColor(parentCheck);

    const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    line.setAttribute('x1', x);
    line.setAttribute('y1', y1);
    line.setAttribute('x2', x);
    line.setAttribute('y2', y2);
    line.setAttribute('stroke', color);
    line.setAttribute('stroke-width', '3');
    line.setAttribute('opacity', '0.5');
    overlay.appendChild(line);
}

// Register the custom checker
Blockly.registry.register(
    Blockly.registry.Type.CONNECTION_CHECKER,
    'TrikeShedConnectionChecker',
    TrikeShedConnectionChecker
);
