"""tspy — TrikeShed Python algebra (tuple-native)"""

from .algebra import (
    # Core algebra
    Join, Twin, Series, MetaSeries, CSeries,
    j, twin, s_, _l, _a, _s, constant,
    # Wire protocol
    FieldSynapse, PointcutEmitterPort,
    # Tensor
    Shape, Tensor, shape_of, scalar_shape, scalar_tensor,
    # Ring buffer
    RingSeries,
    # OpK/ColK
    OpK, ColK, TextK,
)

from .cursor import (
    # Cursor algebra
    ColumnMeta, IOMemento, IoBoolean, IoInt, IoLong, IoFloat, IoDouble,
    IoString, IoLocalDate, IoInstant, IoNothing, IoObject, IoArray, IoBytes,
    ColumnMetaThunk, RowVec, Cursor,
    row_cell, row_vec, cursor,
    select, select_names, exclude, join as cursor_join, combine,
    cursor_alpha, head, tail, meta, column_names, width,
    # FacetedRow isomorphism
    FacetedRow, as_faceted, as_rowvec,
    ColK,
)

from .polyglot import (
    PyenvEmitter,
    install_pointcut_hooks,
    uninstall_pointcut_hooks,
)

from .chronicle import (
    Chronicle, CircularQueue, emit,
    TransitionSplat, FanoutSplat,
    DeliveryOutcome, to_chronology,
    CHRONICLE,
)

__all__ = [
    # Algebra
    'Join', 'Twin', 'Series', 'MetaSeries', 'CSeries',
    'j', 'twin', 's_', '_l', '_a', '_s', 'constant',
    'FieldSynapse', 'PointcutEmitterPort',
    'Shape', 'Tensor', 'shape_of', 'scalar_shape', 'scalar_tensor',
    'RingSeries',
    'OpK', 'ColK', 'TextK',
    # Cursor
    'ColumnMeta', 'IOMemento', 'IoBoolean', 'IoInt', 'IoLong', 'IoFloat', 'IoDouble',
    'IoString', 'IoLocalDate', 'IoInstant', 'IoNothing', 'IoObject', 'IoArray', 'IoBytes',
    'ColumnMetaThunk', 'RowVec', 'Cursor',
    'row_cell', 'row_vec', 'cursor',
    'select', 'select_names', 'exclude', 'cursor_join', 'combine',
    'cursor_alpha', 'head', 'tail', 'meta', 'column_names', 'width',
    'FacetedRow', 'as_faceted', 'as_rowvec',
    'ColK',
    # Polyglot
    'PyenvEmitter', 'install_pointcut_hooks', 'uninstall_pointcut_hooks',
    # Chronicle
    'Chronicle', 'CircularQueue', 'emit',
    'TransitionSplat', 'FanoutSplat',
    'DeliveryOutcome', 'to_chronology',
    'CHRONICLE',
]