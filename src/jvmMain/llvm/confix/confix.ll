; lib/Confix/confix.ll - TrikeShed's Confix scanner, in LLVM IR, over a CharSeries.
;
; Taken from the IR Kotlin/Native makes of borg.trikeshed.parse.confix.Syntax (scan0, scanCbor0 with readLength and parseItem, buildTree, and the
; bounds check of indexed), with the Kotlin runtime taken out: no object headers, no safepoints, no boxed Joins; the lists the Kotlin code
; grows are the arrays of the Flat record the caller hands in.  What is left is the Kotlin scan, step for step.  It uses nothing outside this
; file: no libc, no allocation, no recursion (the open containers of a CBOR or YAML text are frames in `work`), no strings.
;
;   i32 @confix_scan_json(ptr cs, ptr flat)     Syntax.scan0      + buildTree + the bounds check
;   i32 @confix_scan_cbor(ptr cs, ptr flat)     Syntax.scanCbor0  + buildTree + the bounds check
;   i32 @confix_scan_yaml(ptr cs, ptr flat)     Syntax.scanYaml0 (DirectYamlParser) + buildTree + the bounds check
;
; cs is a CharSeries (include/Composite/CharSeries.h): its size and its data are read, from offset 0; pos, limit and mark are not.  The size is
; at most 2^31 - 1, as a Kotlin Int is.  flat is the record include/NixDialect/Confix.td describes.  The result is the fault (0 for none), also in flat.
;
; As Kotlin scans: nothing is checked.  A `]` with nothing open closes nothing, `-+-` is a number, every JSON number is a Double, a CBOR break
; with no indefinite item waiting is a Nothing, a CBOR length of 2^31 or more from four bytes is negative and so indefinite (the Int the four
; bytes make is sign-extended), and the length of a string is the low 32 bits of its Long.  Where Kotlin throws (a read past the end, the
; additional information 28 to 30) there is a fault; where it would never come back (a length that sends the position to or before the
; item) there is the fault Backward.

%confix.chars = type { i32, ptr, i32, i32, i32 }
%confix.flat = type { i32, i32, i32, i32, ptr, ptr, ptr, ptr, ptr, ptr }

attributes #0 = { nounwind "no-builtins" }
attributes #1 = { alwaysinline nounwind "no-builtins" }

; ---- the Flat record's arrays

define internal i8 @at8(ptr %s, i32 %i) #1 {
  %z = zext i32 %i to i64
  %q = getelementptr inbounds i8, ptr %s, i64 %z
  %c = load i8, ptr %q, align 1
  ret i8 %c
}

define internal void @put(ptr %span, ptr %tag, i32 %r, i32 %open, i32 %close, i8 %t) #1 {
  %rz = zext i32 %r to i64
  %r2 = shl i64 %rz, 1
  %sp = getelementptr inbounds i32, ptr %span, i64 %r2
  store i32 %open, ptr %sp, align 4
  %sp1 = getelementptr inbounds i32, ptr %sp, i64 1
  store i32 %close, ptr %sp1, align 4
  %tp = getelementptr inbounds i8, ptr %tag, i64 %rz
  store i8 %t, ptr %tp, align 1
  ret void
}

define internal void @setclose(ptr %span, i32 %r, i32 %close) #1 {
  %rz = zext i32 %r to i64
  %r2 = shl i64 %rz, 1
  %sp = getelementptr inbounds i32, ptr %span, i64 %r2
  %sp1 = getelementptr inbounds i32, ptr %sp, i64 1
  store i32 %close, ptr %sp1, align 4
  ret void
}

define internal i32 @getclose(ptr %span, i32 %r) #1 {
  %rz = zext i32 %r to i64
  %r2 = shl i64 %rz, 1
  %sp = getelementptr inbounds i32, ptr %span, i64 %r2
  %sp1 = getelementptr inbounds i32, ptr %sp, i64 1
  %v = load i32, ptr %sp1, align 4
  ret i32 %v
}

define internal i32 @getopen(ptr %span, i32 %r) #1 {
  %rz = zext i32 %r to i64
  %r2 = shl i64 %rz, 1
  %sp = getelementptr inbounds i32, ptr %span, i64 %r2
  %v = load i32, ptr %sp, align 4
  ret i32 %v
}

; a row for an element whose open and close are known (the `add` of Kotlin's scans); false when the record has no room for it
define internal i1 @add(ptr %span, ptr %tag, ptr %rows, i32 %room, i32 %open, i32 %close, i8 %t) #1 {
entry:
  %r = load i32, ptr %rows, align 4
  %full = icmp uge i32 %r, %room
  br i1 %full, label %no, label %yes
yes:
  call void @put(ptr %span, ptr %tag, i32 %r, i32 %open, i32 %close, i8 %t)
  %r1 = add i32 %r, 1
  store i32 %r1, ptr %rows, align 4
  ret i1 true
no:
  ret i1 false
}

; ---- Syntax.buildTree sorts the rows by (open, slot) as signed Longs ((open shl 32) or slot), so rows with equal opens keep the scanner's order.  The
; JSON and CBOR scanners make their rows in the order of their opens already; the YAML scanner does not (the children of an inline flow are
; placed from line 1), and asks for the sort.  The order goes in the kid array, which is not in use yet (a heap sort of the row numbers, then the
; rows are moved in place along the cycles of the permutation).  The sift of a leaf does nothing, so the heap is made from the last row down.

; (open[a], a) < (open[b], b), open signed
define internal i1 @row_less(ptr %span, i32 %a, i32 %b) #1 {
  %az = zext i32 %a to i64
  %bz = zext i32 %b to i64
  %a2 = shl i64 %az, 1
  %b2 = shl i64 %bz, 1
  %pa = getelementptr inbounds i32, ptr %span, i64 %a2
  %pb = getelementptr inbounds i32, ptr %span, i64 %b2
  %oa = load i32, ptr %pa, align 4
  %ob = load i32, ptr %pb, align 4
  %lt = icmp slt i32 %oa, %ob
  %eq = icmp eq i32 %oa, %ob
  %ab = icmp ult i32 %a, %b
  %tie = and i1 %eq, %ab
  %r = or i1 %lt, %tie
  ret i1 %r
}

; the heap order[root ..< end] with its largest on top
define internal void @sift(ptr %order, ptr %span, i32 %root0, i32 %end) #0 {
entry:
  br label %loop
loop:
  %root = phi i32 [ %root0, %entry ], [ %child2, %swap ]
  %c0 = shl i32 %root, 1
  %child = add i32 %c0, 1
  %has = icmp ult i32 %child, %end
  br i1 %has, label %pick, label %done
pick:
  %ch1 = add i32 %child, 1
  %has2 = icmp ult i32 %ch1, %end
  br i1 %has2, label %cmp2, label %cmp3
cmp2:
  %cz = zext i32 %child to i64
  %op = getelementptr inbounds i32, ptr %order, i64 %cz
  %oc = load i32, ptr %op, align 4
  %op1 = getelementptr inbounds i32, ptr %op, i64 1
  %oc1 = load i32, ptr %op1, align 4
  %less = call i1 @row_less(ptr %span, i32 %oc, i32 %oc1)
  %childb = select i1 %less, i32 %ch1, i32 %child
  br label %cmp3
cmp3:
  %child2 = phi i32 [ %child, %pick ], [ %childb, %cmp2 ]
  %rz = zext i32 %root to i64
  %rp = getelementptr inbounds i32, ptr %order, i64 %rz
  %ro = load i32, ptr %rp, align 4
  %c2z = zext i32 %child2 to i64
  %cp = getelementptr inbounds i32, ptr %order, i64 %c2z
  %co = load i32, ptr %cp, align 4
  %lt = call i1 @row_less(ptr %span, i32 %ro, i32 %co)
  br i1 %lt, label %swap, label %done
swap:
  store i32 %co, ptr %rp, align 4
  store i32 %ro, ptr %cp, align 4
  br label %loop
done:
  ret void
}

define internal void @sort_rows(ptr %flat, i32 %total) #0 {
entry:
  %p4 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 4
  %span = load ptr, ptr %p4, align 8
  %p5 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 5
  %tag = load ptr, ptr %p5, align 8
  %p8 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 8
  %order = load ptr, ptr %p8, align 8
  br label %initl
initl:
  %ii = phi i32 [ 0, %entry ], [ %ii1, %initb ]
  %iic = icmp ult i32 %ii, %total
  br i1 %iic, label %initb, label %heapify
initb:
  %iz = zext i32 %ii to i64
  %ip = getelementptr inbounds i32, ptr %order, i64 %iz
  store i32 %ii, ptr %ip, align 4
  %ii1 = add i32 %ii, 1
  br label %initl
heapify:
  br label %hl
hl:
  %hs = phi i32 [ %total, %heapify ], [ %hs1, %hb ]
  %hc = icmp ugt i32 %hs, 0
  br i1 %hc, label %hb, label %extract
hb:
  %hs1 = sub i32 %hs, 1
  call void @sift(ptr %order, ptr %span, i32 %hs1, i32 %total)
  br label %hl
extract:
  br label %el
el:
  %e = phi i32 [ %total, %extract ], [ %e1, %eb ]
  %ec = icmp ugt i32 %e, 1
  br i1 %ec, label %eb, label %gather
eb:
  %e1 = sub i32 %e, 1
  %e1z = zext i32 %e1 to i64
  %ep = getelementptr inbounds i32, ptr %order, i64 %e1z
  %eo = load i32, ptr %ep, align 4
  %o0v = load i32, ptr %order, align 4
  store i32 %eo, ptr %order, align 4
  store i32 %o0v, ptr %ep, align 4
  call void @sift(ptr %order, ptr %span, i32 0, i32 %e1)
  br label %el
gather:
  br label %gl
gl:
  %gi = phi i32 [ 0, %gather ], [ %gi1, %gnext ]
  %gc = icmp ult i32 %gi, %total
  br i1 %gc, label %gb, label %ret
gb:
  %giz = zext i32 %gi to i64
  %gop = getelementptr inbounds i32, ptr %order, i64 %giz
  %gov = load i32, ptr %gop, align 4
  %fixed = icmp eq i32 %gov, %gi
  br i1 %fixed, label %gnext, label %cyc
cyc:
  %gi2 = shl i64 %giz, 1
  %sp0 = getelementptr inbounds i32, ptr %span, i64 %gi2
  %t_open = load i32, ptr %sp0, align 4
  %sp1 = getelementptr inbounds i32, ptr %sp0, i64 1
  %t_close = load i32, ptr %sp1, align 4
  %tgp = getelementptr inbounds i8, ptr %tag, i64 %giz
  %t_tag = load i8, ptr %tgp, align 1
  br label %cl
cl:
  %j = phi i32 [ %gi, %cyc ], [ %src, %cmove ]
  %jz = zext i32 %j to i64
  %jop = getelementptr inbounds i32, ptr %order, i64 %jz
  %src = load i32, ptr %jop, align 4
  store i32 %j, ptr %jop, align 4
  %j2 = shl i64 %jz, 1
  %jsp0 = getelementptr inbounds i32, ptr %span, i64 %j2
  %jsp1 = getelementptr inbounds i32, ptr %jsp0, i64 1
  %jtp = getelementptr inbounds i8, ptr %tag, i64 %jz
  %last = icmp eq i32 %src, %gi
  br i1 %last, label %cend, label %cmove
cmove:
  %srz = zext i32 %src to i64
  %sr2 = shl i64 %srz, 1
  %ssp0 = getelementptr inbounds i32, ptr %span, i64 %sr2
  %ssp1 = getelementptr inbounds i32, ptr %ssp0, i64 1
  %so = load i32, ptr %ssp0, align 4
  %sc = load i32, ptr %ssp1, align 4
  %stp = getelementptr inbounds i8, ptr %tag, i64 %srz
  %st = load i8, ptr %stp, align 1
  store i32 %so, ptr %jsp0, align 4
  store i32 %sc, ptr %jsp1, align 4
  store i8 %st, ptr %jtp, align 1
  br label %cl
cend:
  store i32 %t_open, ptr %jsp0, align 4
  store i32 %t_close, ptr %jsp1, align 4
  store i8 %t_tag, ptr %jtp, align 1
  br label %gnext
gnext:
  %gi1 = add i32 %gi, 1
  br label %gl
ret:
  ret void
}

; ---- Syntax.buildTree: the rows are in the order of their opens already, so the sort is the identity; then the stack sweep
; (a span's parent is the innermost open span that still covers it), the child counts, their prefix sums, and the fill.
define internal void @build_tree(ptr %flat, i32 %total, i32 %sort) #0 {
entry:
  %p4 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 4
  %span = load ptr, ptr %p4, align 8
  %p6 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 6
  %depth = load ptr, ptr %p6, align 8
  %p7 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 7
  %start = load ptr, ptr %p7, align 8
  %p8 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 8
  %kid = load ptr, ptr %p8, align 8
  %p9 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 9
  %stack = load ptr, ptr %p9, align 8
  %tz = zext i32 %total to i64
  %parent = getelementptr inbounds i32, ptr %stack, i64 %tz
  %t2 = shl i64 %tz, 1
  %fill = getelementptr inbounds i32, ptr %stack, i64 %t2
  %dosort = icmp ne i32 %sort, 0
  br i1 %dosort, label %sortit, label %zero
sortit:
  call void @sort_rows(ptr %flat, i32 %total)
  br label %zero
zero:
  %zi = phi i32 [ 0, %entry ], [ 0, %sortit ], [ %zi1, %zerob ]
  %zc = icmp ule i32 %zi, %total
  br i1 %zc, label %zerob, label %sweep
zerob:
  %zz = zext i32 %zi to i64
  %zp = getelementptr inbounds i32, ptr %start, i64 %zz
  store i32 0, ptr %zp, align 4
  %zi1 = add i32 %zi, 1
  br label %zero
sweep:
  %k = phi i32 [ 0, %zero ], [ %k1, %next ]
  %top0 = phi i32 [ 0, %zero ], [ %top2, %next ]
  %kc = icmp ult i32 %k, %total
  br i1 %kc, label %body, label %prefix
body:
  %ck = call i32 @getclose(ptr %span, i32 %k)
  br label %pop
pop:
  %top = phi i32 [ %top0, %body ], [ %topm, %popb ]
  %has = icmp ne i32 %top, 0
  br i1 %has, label %popchk, label %popdone
popchk:
  %ti = sub i32 %top, 1
  %tiz = zext i32 %ti to i64
  %srp = getelementptr inbounds i32, ptr %stack, i64 %tiz
  %srow = load i32, ptr %srp, align 4
  %cs = call i32 @getclose(ptr %span, i32 %srow)
  %less = icmp slt i32 %cs, %ck
  br i1 %less, label %popb, label %popdone
popb:
  %topm = sub i32 %top, 1
  br label %pop
popdone:
  %kz = zext i32 %k to i64
  %hast = icmp ne i32 %top, 0
  br i1 %hast, label %hasparent, label %havepar
hasparent:
  %tj = sub i32 %top, 1
  %tjz = zext i32 %tj to i64
  %pp = getelementptr inbounds i32, ptr %stack, i64 %tjz
  %prow = load i32, ptr %pp, align 4
  br label %havepar
havepar:
  %par = phi i32 [ -1, %popdone ], [ %prow, %hasparent ]
  %pap = getelementptr inbounds i32, ptr %parent, i64 %kz
  store i32 %par, ptr %pap, align 4
  %dp = getelementptr inbounds i32, ptr %depth, i64 %kz
  store i32 %top, ptr %dp, align 4
  br i1 %hast, label %count, label %push
count:
  %par1 = add i32 %par, 1
  %par1z = zext i32 %par1 to i64
  %scp = getelementptr inbounds i32, ptr %start, i64 %par1z
  %sc = load i32, ptr %scp, align 4
  %sc1 = add i32 %sc, 1
  store i32 %sc1, ptr %scp, align 4
  br label %push
push:
  %topz = zext i32 %top to i64
  %stp = getelementptr inbounds i32, ptr %stack, i64 %topz
  store i32 %k, ptr %stp, align 4
  %top2 = add i32 %top, 1
  br label %next
next:
  %k1 = add i32 %k, 1
  br label %sweep
prefix:
  %q = phi i32 [ 0, %sweep ], [ %q1, %prefixb ]
  %qc = icmp ult i32 %q, %total
  br i1 %qc, label %prefixb, label %copy
prefixb:
  %qz = zext i32 %q to i64
  %qp = getelementptr inbounds i32, ptr %start, i64 %qz
  %qa = load i32, ptr %qp, align 4
  %qp1 = getelementptr inbounds i32, ptr %qp, i64 1
  %qb = load i32, ptr %qp1, align 4
  %qs = add i32 %qb, %qa
  store i32 %qs, ptr %qp1, align 4
  %q1 = add i32 %q, 1
  br label %prefix
copy:
  %ci = phi i32 [ 0, %prefix ], [ %ci1, %copyb ]
  %cc = icmp ult i32 %ci, %total
  br i1 %cc, label %copyb, label %kids
copyb:
  %cz = zext i32 %ci to i64
  %cs0 = getelementptr inbounds i32, ptr %start, i64 %cz
  %cv = load i32, ptr %cs0, align 4
  %cf = getelementptr inbounds i32, ptr %fill, i64 %cz
  store i32 %cv, ptr %cf, align 4
  %ci1 = add i32 %ci, 1
  br label %copy
kids:
  %ki = phi i32 [ 0, %copy ], [ %ki1, %kidnext ]
  %kcnd = icmp ult i32 %ki, %total
  br i1 %kcnd, label %kidb, label %done
kidb:
  %kiz = zext i32 %ki to i64
  %kpp = getelementptr inbounds i32, ptr %parent, i64 %kiz
  %kpar = load i32, ptr %kpp, align 4
  %kok = icmp sge i32 %kpar, 0
  br i1 %kok, label %kidput, label %kidnext
kidput:
  %kparz = zext i32 %kpar to i64
  %kfp = getelementptr inbounds i32, ptr %fill, i64 %kparz
  %kslot = load i32, ptr %kfp, align 4
  %kslotz = zext i32 %kslot to i64
  %kkp = getelementptr inbounds i32, ptr %kid, i64 %kslotz
  store i32 %ki, ptr %kkp, align 4
  %kslot1 = add i32 %kslot, 1
  store i32 %kslot1, ptr %kfp, align 4
  br label %kidnext
kidnext:
  %ki1 = add i32 %ki, 1
  br label %kids
done:
  ret void
}

; Syntax.indexed's check, for the rows in order: the first row whose span is past the text, else -1.
define internal i32 @bounds(ptr %span, i32 %total, i32 %n) #0 {
entry:
  br label %loop
loop:
  %k = phi i32 [ 0, %entry ], [ %k1, %next ]
  %c = icmp ult i32 %k, %total
  br i1 %c, label %body, label %none
body:
  %o = call i32 @getopen(ptr %span, i32 %k)
  %e = call i32 @getclose(ptr %span, i32 %k)
  %rev = icmp slt i32 %e, %o
  %nn = icmp sge i32 %o, 0
  %inb = icmp slt i32 %e, %n
  %fit = and i1 %nn, %inb
  %ok = or i1 %rev, %fit
  br i1 %ok, label %next, label %bad
next:
  %k1 = add i32 %k, 1
  br label %loop
bad:
  ret i32 %k
none:
  ret i32 -1
}

; the end of every scan: the tree and the check when nothing went wrong, then total, fault and at into the record
define internal i32 @fin(ptr %flat, i32 %total, i32 %n, i32 %fault, i32 %at, i32 %sort) #0 {
entry:
  %ok = icmp eq i32 %fault, 0
  br i1 %ok, label %tree, label %out
tree:
  call void @build_tree(ptr %flat, i32 %total, i32 %sort)
  %p4 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 4
  %span = load ptr, ptr %p4, align 8
  %bad = call i32 @bounds(ptr %span, i32 %total, i32 %n)
  %isbad = icmp sge i32 %bad, 0
  %f2 = select i1 %isbad, i32 3, i32 0
  %a2 = select i1 %isbad, i32 %bad, i32 0
  br label %out
out:
  %ff = phi i32 [ %fault, %entry ], [ %f2, %tree ]
  %aa = phi i32 [ %at, %entry ], [ %a2, %tree ]
  %q0 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 0
  store i32 %total, ptr %q0, align 4
  %q1 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 1
  store i32 %ff, ptr %q1, align 4
  %q2 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 2
  store i32 %aa, ptr %q2, align 4
  ret i32 %ff
}

; ---- Syntax.scan0

; Kotlin's push: a row for an element that is still open, on the stack of open rows (the stack is the front of `work`)
define internal i1 @jpush(ptr %span, ptr %tag, ptr %work, ptr %rows, ptr %sp, i32 %room, i32 %open, i8 %t) #1 {
entry:
  %r = load i32, ptr %rows, align 4
  %full = icmp uge i32 %r, %room
  br i1 %full, label %no, label %yes
yes:
  call void @put(ptr %span, ptr %tag, i32 %r, i32 %open, i32 %open, i8 %t)
  %s0 = load i32, ptr %sp, align 4
  %sz = zext i32 %s0 to i64
  %wp = getelementptr inbounds i32, ptr %work, i64 %sz
  store i32 %r, ptr %wp, align 4
  %s1 = add i32 %s0, 1
  store i32 %s1, ptr %sp, align 4
  %r1 = add i32 %r, 1
  store i32 %r1, ptr %rows, align 4
  ret i1 true
no:
  ret i1 false
}

; Kotlin's pop: with nothing open it does nothing
define internal void @jpop(ptr %span, ptr %work, ptr %sp, i32 %close) #1 {
entry:
  %s0 = load i32, ptr %sp, align 4
  %z = icmp eq i32 %s0, 0
  br i1 %z, label %out, label %go
go:
  %s1 = sub i32 %s0, 1
  store i32 %s1, ptr %sp, align 4
  %sz = zext i32 %s1 to i64
  %wp = getelementptr inbounds i32, ptr %work, i64 %sz
  %r = load i32, ptr %wp, align 4
  call void @setclose(ptr %span, i32 %r, i32 %close)
  br label %out
out:
  ret void
}

define i32 @confix_scan_json(ptr %cs, ptr %flat) #0 {
entry:
  %n = load i32, ptr %cs, align 8
  %dp = getelementptr inbounds %confix.chars, ptr %cs, i32 0, i32 1
  %s = load ptr, ptr %dp, align 8
  %rp = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 3
  %room = load i32, ptr %rp, align 4
  %p4 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 4
  %span = load ptr, ptr %p4, align 8
  %p5 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 5
  %tag = load ptr, ptr %p5, align 8
  %p9 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 9
  %work = load ptr, ptr %p9, align 8
  %rows = alloca i32, align 4
  %sp = alloca i32, align 4
  %i = alloca i32, align 4
  %inq = alloca i1, align 1
  %esc = alloca i1, align 1
  store i32 0, ptr %rows, align 4
  store i32 0, ptr %sp, align 4
  store i32 0, ptr %i, align 4
  store i1 false, ptr %inq, align 1
  store i1 false, ptr %esc, align 1
  br label %loop

loop:
  %iv = load i32, ptr %i, align 4
  %end = icmp uge i32 %iv, %n
  br i1 %end, label %flush, label %body

body:
  %c = call i8 @at8(ptr %s, i32 %iv)
  %q = load i1, ptr %inq, align 1
  br i1 %q, label %inquote, label %outside

inquote:
  %e = load i1, ptr %esc, align 1
  br i1 %e, label %unesc, label %q2
unesc:
  store i1 false, ptr %esc, align 1
  br label %next
q2:
  %isbs = icmp eq i8 %c, 92
  br i1 %isbs, label %setesc, label %q3
setesc:
  store i1 true, ptr %esc, align 1
  br label %next
q3:
  %isq = icmp eq i8 %c, 34
  br i1 %isq, label %closestr, label %next
closestr:
  store i1 false, ptr %inq, align 1
  call void @jpop(ptr %span, ptr %work, ptr %sp, i32 %iv)
  br label %next

outside:
  switch i8 %c, label %other [
    i8 123, label %open_obj
    i8 91, label %open_arr
    i8 125, label %shut
    i8 93, label %shut
    i8 34, label %open_str
    i8 116, label %lit_t
    i8 102, label %lit_f
    i8 110, label %lit_n
    i8 45, label %number
    i8 43, label %number
  ]
other:
  %dd = sub i8 %c, 48
  %isd = icmp ule i8 %dd, 9
  br i1 %isd, label %number, label %next

open_obj:
  %okA = call i1 @jpush(ptr %span, ptr %tag, ptr %work, ptr %rows, ptr %sp, i32 %room, i32 %iv, i8 5)
  br i1 %okA, label %next, label %roomfault
open_arr:
  %okB = call i1 @jpush(ptr %span, ptr %tag, ptr %work, ptr %rows, ptr %sp, i32 %room, i32 %iv, i8 6)
  br i1 %okB, label %next, label %roomfault
shut:
  call void @jpop(ptr %span, ptr %work, ptr %sp, i32 %iv)
  br label %next
open_str:
  %okC = call i1 @jpush(ptr %span, ptr %tag, ptr %work, ptr %rows, ptr %sp, i32 %room, i32 %iv, i8 4)
  store i1 true, ptr %inq, align 1
  br i1 %okC, label %next, label %roomfault

lit_t:
  %rt = sub i32 %n, %iv
  %okt = icmp ugt i32 %rt, 3
  br i1 %okt, label %lt1, label %next
lt1:
  %t1i = add i32 %iv, 1
  %t1 = call i8 @at8(ptr %s, i32 %t1i)
  %t1ok = icmp eq i8 %t1, 114
  %t2i = add i32 %iv, 2
  %t2 = call i8 @at8(ptr %s, i32 %t2i)
  %t2ok = icmp eq i8 %t2, 117
  %t3i = add i32 %iv, 3
  %t3 = call i8 @at8(ptr %s, i32 %t3i)
  %t3ok = icmp eq i8 %t3, 101
  %ta = and i1 %t1ok, %t2ok
  %tb = and i1 %ta, %t3ok
  br i1 %tb, label %lt_ok, label %next
lt_ok:
  %okD = call i1 @add(ptr %span, ptr %tag, ptr %rows, i32 %room, i32 %iv, i32 %t3i, i8 1)
  store i32 %t3i, ptr %i, align 4
  br i1 %okD, label %next, label %roomfault

lit_f:
  %rf = sub i32 %n, %iv
  %okf = icmp ugt i32 %rf, 4
  br i1 %okf, label %lf1, label %next
lf1:
  %f1i = add i32 %iv, 1
  %f1 = call i8 @at8(ptr %s, i32 %f1i)
  %f1ok = icmp eq i8 %f1, 97
  %f2i = add i32 %iv, 2
  %f2 = call i8 @at8(ptr %s, i32 %f2i)
  %f2ok = icmp eq i8 %f2, 108
  %f3i = add i32 %iv, 3
  %f3 = call i8 @at8(ptr %s, i32 %f3i)
  %f3ok = icmp eq i8 %f3, 115
  %f4i = add i32 %iv, 4
  %f4 = call i8 @at8(ptr %s, i32 %f4i)
  %f4ok = icmp eq i8 %f4, 101
  %fa = and i1 %f1ok, %f2ok
  %fb = and i1 %f3ok, %f4ok
  %fc = and i1 %fa, %fb
  br i1 %fc, label %lf_ok, label %next
lf_ok:
  %okE = call i1 @add(ptr %span, ptr %tag, ptr %rows, i32 %room, i32 %iv, i32 %f4i, i8 1)
  store i32 %f4i, ptr %i, align 4
  br i1 %okE, label %next, label %roomfault

lit_n:
  %rn = sub i32 %n, %iv
  %okn = icmp ugt i32 %rn, 3
  br i1 %okn, label %ln1, label %next
ln1:
  %n1i = add i32 %iv, 1
  %n1 = call i8 @at8(ptr %s, i32 %n1i)
  %n1ok = icmp eq i8 %n1, 117
  %n2i = add i32 %iv, 2
  %n2 = call i8 @at8(ptr %s, i32 %n2i)
  %n2ok = icmp eq i8 %n2, 108
  %n3i = add i32 %iv, 3
  %n3 = call i8 @at8(ptr %s, i32 %n3i)
  %n3ok = icmp eq i8 %n3, 108
  %na = and i1 %n1ok, %n2ok
  %nb = and i1 %na, %n3ok
  br i1 %nb, label %ln_ok, label %next
ln_ok:
  %okF = call i1 @add(ptr %span, ptr %tag, ptr %rows, i32 %room, i32 %iv, i32 %n3i, i8 0)
  store i32 %n3i, ptr %i, align 4
  br i1 %okF, label %next, label %roomfault

number:
  br label %numl
numl:
  %j = phi i32 [ %iv, %number ], [ %j1, %numb ]
  %jin = icmp ult i32 %j, %n
  br i1 %jin, label %numc, label %numend
numc:
  %d = call i8 @at8(ptr %s, i32 %j)
  %dg = sub i8 %d, 48
  %d1 = icmp ule i8 %dg, 9
  %d2 = icmp eq i8 %d, 46
  %dl = or i8 %d, 32
  %d3 = icmp eq i8 %dl, 101
  %d4 = icmp eq i8 %d, 43
  %d5 = icmp eq i8 %d, 45
  %da = or i1 %d1, %d2
  %db = or i1 %d3, %d4
  %dc = or i1 %db, %d5
  %dd2 = or i1 %da, %dc
  br i1 %dd2, label %numb, label %numend
numb:
  %j1 = add i32 %j, 1
  br label %numl
numend:
  %je = phi i32 [ %j, %numl ], [ %j, %numc ]
  %jm = sub i32 %je, 1
  %okG = call i1 @add(ptr %span, ptr %tag, ptr %rows, i32 %room, i32 %iv, i32 %jm, i8 3)
  store i32 %je, ptr %i, align 4
  br i1 %okG, label %loop, label %roomfault

next:
  %ivn = load i32, ptr %i, align 4
  %ip1 = add i32 %ivn, 1
  store i32 %ip1, ptr %i, align 4
  br label %loop

flush:
  %nm1 = sub i32 %n, 1
  br label %fl
fl:
  %fs = load i32, ptr %sp, align 4
  %fnz = icmp ne i32 %fs, 0
  br i1 %fnz, label %flb, label %built
flb:
  call void @jpop(ptr %span, ptr %work, ptr %sp, i32 %nm1)
  br label %fl
built:
  %total = load i32, ptr %rows, align 4
  %r = call i32 @fin(ptr %flat, i32 %total, i32 %n, i32 0, i32 0, i32 0)
  ret i32 %r

roomfault:
  %rtot = load i32, ptr %rows, align 4
  %rat = load i32, ptr %i, align 4
  %rr = call i32 @fin(ptr %flat, i32 %rtot, i32 %n, i32 5, i32 %rat, i32 0)
  ret i32 %rr
}

; ---- Syntax.scanCbor0

; Kotlin's readLength at `p`, for the additional information `ai`: { the Long, where the text goes on, the fault }.  On a fault the second
; is the offset it was found at.  The fault is 1 for a read past the end, 2 for the additional information 28 to 30 (Kotlin's error).
define internal { i64, i32, i32 } @readlen(ptr %s, i32 %n, i32 %p, i32 %ai) #0 {
entry:
  %small = icmp ule i32 %ai, 23
  br i1 %small, label %direct, label %wide
direct:
  %dv = zext i32 %ai to i64
  %d0 = insertvalue { i64, i32, i32 } undef, i64 %dv, 0
  %d1 = insertvalue { i64, i32, i32 } %d0, i32 %p, 1
  %d2 = insertvalue { i64, i32, i32 } %d1, i32 0, 2
  ret { i64, i32, i32 } %d2
wide:
  %pz = zext i32 %p to i64
  %nz = zext i32 %n to i64
  switch i32 %ai, label %reserved [
    i32 24, label %a24
    i32 25, label %a25
    i32 26, label %a26
    i32 27, label %a27
    i32 31, label %a31
  ]
a24:
  %e24 = add i64 %pz, 1
  %o24 = icmp ule i64 %e24, %nz
  br i1 %o24, label %a24ok, label %trunc
a24ok:
  %b24 = call i8 @at8(ptr %s, i32 %p)
  %v24 = zext i8 %b24 to i64
  %x24 = add i32 %p, 1
  %r24a = insertvalue { i64, i32, i32 } undef, i64 %v24, 0
  %r24b = insertvalue { i64, i32, i32 } %r24a, i32 %x24, 1
  %r24c = insertvalue { i64, i32, i32 } %r24b, i32 0, 2
  ret { i64, i32, i32 } %r24c
a25:
  %e25 = add i64 %pz, 2
  %o25 = icmp ule i64 %e25, %nz
  br i1 %o25, label %a25ok, label %trunc
a25ok:
  %b250 = call i8 @at8(ptr %s, i32 %p)
  %p251 = add i32 %p, 1
  %b251 = call i8 @at8(ptr %s, i32 %p251)
  %z250 = zext i8 %b250 to i32
  %z251 = zext i8 %b251 to i32
  %h250 = shl i32 %z250, 8
  %v25i = or i32 %h250, %z251
  %v25 = sext i32 %v25i to i64
  %x25 = add i32 %p, 2
  %r25a = insertvalue { i64, i32, i32 } undef, i64 %v25, 0
  %r25b = insertvalue { i64, i32, i32 } %r25a, i32 %x25, 1
  %r25c = insertvalue { i64, i32, i32 } %r25b, i32 0, 2
  ret { i64, i32, i32 } %r25c
a26:
  %e26 = add i64 %pz, 4
  %o26 = icmp ule i64 %e26, %nz
  br i1 %o26, label %a26ok, label %trunc
a26ok:
  %b260 = call i8 @at8(ptr %s, i32 %p)
  %p261 = add i32 %p, 1
  %b261 = call i8 @at8(ptr %s, i32 %p261)
  %p262 = add i32 %p, 2
  %b262 = call i8 @at8(ptr %s, i32 %p262)
  %p263 = add i32 %p, 3
  %b263 = call i8 @at8(ptr %s, i32 %p263)
  %z260 = zext i8 %b260 to i32
  %z261 = zext i8 %b261 to i32
  %z262 = zext i8 %b262 to i32
  %z263 = zext i8 %b263 to i32
  %h260 = shl i32 %z260, 24
  %h261 = shl i32 %z261, 16
  %h262 = shl i32 %z262, 8
  %m260 = or i32 %h260, %h261
  %m261 = or i32 %m260, %h262
  %v26i = or i32 %m261, %z263
  %v26 = sext i32 %v26i to i64
  %x26 = add i32 %p, 4
  %r26a = insertvalue { i64, i32, i32 } undef, i64 %v26, 0
  %r26b = insertvalue { i64, i32, i32 } %r26a, i32 %x26, 1
  %r26c = insertvalue { i64, i32, i32 } %r26b, i32 0, 2
  ret { i64, i32, i32 } %r26c
a27:
  %e27 = add i64 %pz, 8
  %o27 = icmp ule i64 %e27, %nz
  br i1 %o27, label %a27l, label %trunc
a27l:
  %k27 = phi i32 [ 0, %a27 ], [ %k271, %a27b ]
  %acc = phi i64 [ 0, %a27 ], [ %acc2, %a27b ]
  %kc27 = icmp ult i32 %k27, 8
  br i1 %kc27, label %a27b, label %a27d
a27b:
  %pk = add i32 %p, %k27
  %bk = call i8 @at8(ptr %s, i32 %pk)
  %zk = zext i8 %bk to i64
  %sh = shl i64 %acc, 8
  %acc2 = or i64 %sh, %zk
  %k271 = add i32 %k27, 1
  br label %a27l
a27d:
  %x27 = add i32 %p, 8
  %r27a = insertvalue { i64, i32, i32 } undef, i64 %acc, 0
  %r27b = insertvalue { i64, i32, i32 } %r27a, i32 %x27, 1
  %r27c = insertvalue { i64, i32, i32 } %r27b, i32 0, 2
  ret { i64, i32, i32 } %r27c
a31:
  %r31a = insertvalue { i64, i32, i32 } undef, i64 -1, 0
  %r31b = insertvalue { i64, i32, i32 } %r31a, i32 %p, 1
  %r31c = insertvalue { i64, i32, i32 } %r31b, i32 0, 2
  ret { i64, i32, i32 } %r31c
reserved:
  %hp = sub i32 %p, 1
  %rra = insertvalue { i64, i32, i32 } undef, i64 0, 0
  %rrb = insertvalue { i64, i32, i32 } %rra, i32 %hp, 1
  %rrc = insertvalue { i64, i32, i32 } %rrb, i32 2, 2
  ret { i64, i32, i32 } %rrc
trunc:
  %ge = icmp uge i32 %p, %n
  %at = select i1 %ge, i32 %p, i32 %n
  %tra = insertvalue { i64, i32, i32 } undef, i64 0, 0
  %trb = insertvalue { i64, i32, i32 } %tra, i32 %at, 1
  %trc = insertvalue { i64, i32, i32 } %trb, i32 1, 2
  ret { i64, i32, i32 } %trc
}

; The scan is Kotlin's parseItem with its recursion turned into frames.  A frame is three u32 in `work`: the row of the open container, how many
; items it still takes (a definite one) and flags: 1 indefinite, 2 a map, 4 "its next item is a value": in an indefinite map the key and its
; value are read with one test of the break byte between pairs, not one between them.  Rows are made as their elements open, so they are in the
; order of their opens as buildTree needs, which is the order Kotlin sorts them into.
define i32 @confix_scan_cbor(ptr %cs, ptr %flat) #0 {
entry:
  %n = load i32, ptr %cs, align 8
  %dp = getelementptr inbounds %confix.chars, ptr %cs, i32 0, i32 1
  %s = load ptr, ptr %dp, align 8
  %rp = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 3
  %room = load i32, ptr %rp, align 4
  %p4 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 4
  %span = load ptr, ptr %p4, align 8
  %p5 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 5
  %tag = load ptr, ptr %p5, align 8
  %p9 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 9
  %work = load ptr, ptr %p9, align 8
  %pos = alloca i32, align 4
  %rows = alloca i32, align 4
  %fp = alloca i32, align 4
  %fault = alloca i32, align 4
  %fat = alloca i32, align 4
  store i32 0, ptr %pos, align 4
  store i32 0, ptr %rows, align 4
  store i32 0, ptr %fp, align 4
  store i32 0, ptr %fault, align 4
  store i32 0, ptr %fat, align 4
  br label %top

top:
  %fpv = load i32, ptr %fp, align 4
  %noframe = icmp eq i32 %fpv, 0
  br i1 %noframe, label %top_root, label %top_frame

top_root:
  %pr = load i32, ptr %pos, align 4
  %more = icmp slt i32 %pr, %n
  br i1 %more, label %parse, label %end

top_frame:
  %fi = sub i32 %fpv, 1
  %fz = zext i32 %fi to i64
  %f3 = mul i64 %fz, 3
  %fb = getelementptr inbounds i32, ptr %work, i64 %f3
  %frow = load i32, ptr %fb, align 4
  %fremp = getelementptr inbounds i32, ptr %fb, i64 1
  %frem = load i32, ptr %fremp, align 4
  %fflp = getelementptr inbounds i32, ptr %fb, i64 2
  %ffl = load i32, ptr %fflp, align 4
  %fi1 = and i32 %ffl, 1
  %indef = icmp ne i32 %fi1, 0
  br i1 %indef, label %f_indef, label %f_def

f_def:
  %rem_gt = icmp sgt i32 %frem, 0
  br i1 %rem_gt, label %f_def_take, label %finish
f_def_take:
  %rem1 = sub i32 %frem, 1
  store i32 %rem1, ptr %fremp, align 4
  br label %parse

f_indef:
  %pd = and i32 %ffl, 4
  %pending = icmp ne i32 %pd, 0
  br i1 %pending, label %f_value, label %f_chk
f_value:
  %fl_clr = and i32 %ffl, -5
  store i32 %fl_clr, ptr %fflp, align 4
  br label %parse
f_chk:
  %pc = load i32, ptr %pos, align 4
  %lt = icmp slt i32 %pc, %n
  br i1 %lt, label %f_chk2, label %finish
f_chk2:
  %inr = icmp ult i32 %pc, %n
  br i1 %inr, label %f_chk3, label %trunc_pc
f_chk3:
  %bb = call i8 @at8(ptr %s, i32 %pc)
  %isbrk = icmp eq i8 %bb, -1
  br i1 %isbrk, label %finish, label %f_take
f_take:
  %fm = and i32 %ffl, 2
  %ismap = icmp ne i32 %fm, 0
  %fl_set = or i32 %ffl, 4
  %fl_new = select i1 %ismap, i32 %fl_set, i32 %ffl
  store i32 %fl_new, ptr %fflp, align 4
  br label %parse

finish:
  %pf = load i32, ptr %pos, align 4
  %inb = icmp slt i32 %pf, %n
  %consume = and i1 %indef, %inb
  %pf1 = add i32 %pf, 1
  %pf2 = select i1 %consume, i32 %pf1, i32 %pf
  store i32 %pf2, ptr %pos, align 4
  %fclose = sub i32 %pf2, 1
  call void @setclose(ptr %span, i32 %frow, i32 %fclose)
  store i32 %fi, ptr %fp, align 4
  br label %top

parse:
  %pp = load i32, ptr %pos, align 4
  %pin = icmp ult i32 %pp, %n
  br i1 %pin, label %head, label %trunc_pp

head:
  %ib = call i8 @at8(ptr %s, i32 %pp)
  %ibz = zext i8 %ib to i32
  %major = lshr i32 %ibz, 5
  %ai = and i32 %ibz, 31
  %p1h = add i32 %pp, 1
  switch i32 %major, label %m7 [
    i32 0, label %m01
    i32 1, label %m01
    i32 2, label %m23
    i32 3, label %m23
    i32 4, label %m45
    i32 5, label %m45
    i32 6, label %m6
  ]

m01:
  %r01 = call { i64, i32, i32 } @readlen(ptr %s, i32 %n, i32 %p1h, i32 %ai)
  %f01 = extractvalue { i64, i32, i32 } %r01, 2
  %bad01 = icmp ne i32 %f01, 0
  br i1 %bad01, label %rl_fault01, label %m01b
rl_fault01:
  %a01 = extractvalue { i64, i32, i32 } %r01, 1
  br label %rl_fault
m01b:
  %nx01 = extractvalue { i64, i32, i32 } %r01, 1
  %c01 = sub i32 %nx01, 1
  %ok01 = call i1 @add(ptr %span, ptr %tag, ptr %rows, i32 %room, i32 %pp, i32 %c01, i8 2)
  br i1 %ok01, label %m01c, label %roomfault
m01c:
  store i32 %nx01, ptr %pos, align 4
  br label %top

m23:
  %r23 = call { i64, i32, i32 } @readlen(ptr %s, i32 %n, i32 %p1h, i32 %ai)
  %f23 = extractvalue { i64, i32, i32 } %r23, 2
  %bad23 = icmp ne i32 %f23, 0
  br i1 %bad23, label %rl_fault23, label %m23b
rl_fault23:
  %a23 = extractvalue { i64, i32, i32 } %r23, 1
  br label %rl_fault
m23b:
  %len23 = extractvalue { i64, i32, i32 } %r23, 0
  %nx23 = extractvalue { i64, i32, i32 } %r23, 1
  %indef23 = icmp slt i64 %len23, 0
  br i1 %indef23, label %m23_skip, label %m23_item
m23_skip:
  store i32 %nx23, ptr %pos, align 4
  br label %top
m23_item:
  %l32 = trunc i64 %len23 to i32
  %np23 = add i32 %nx23, %l32
  %c23 = sub i32 %np23, 1
  %fwd = icmp sgt i32 %np23, %pp
  br i1 %fwd, label %m23_add, label %m23_back
m23_back:
  %negp = icmp slt i32 %np23, 0
  br i1 %negp, label %trunc_np, label %backward
m23_add:
  %is2 = icmp eq i32 %major, 2
  %t23 = select i1 %is2, i8 7, i8 4
  %ok23 = call i1 @add(ptr %span, ptr %tag, ptr %rows, i32 %room, i32 %pp, i32 %c23, i8 %t23)
  br i1 %ok23, label %m23_done, label %roomfault
m23_done:
  store i32 %np23, ptr %pos, align 4
  br label %top

m45:
  %r45 = call { i64, i32, i32 } @readlen(ptr %s, i32 %n, i32 %p1h, i32 %ai)
  %f45 = extractvalue { i64, i32, i32 } %r45, 2
  %bad45 = icmp ne i32 %f45, 0
  br i1 %bad45, label %rl_fault45, label %m45b
rl_fault45:
  %a45 = extractvalue { i64, i32, i32 } %r45, 1
  br label %rl_fault
m45b:
  %len45 = extractvalue { i64, i32, i32 } %r45, 0
  %nx45 = extractvalue { i64, i32, i32 } %r45, 1
  %is4 = icmp eq i32 %major, 4
  %t45 = select i1 %is4, i8 6, i8 5
  %ok45 = call i1 @add(ptr %span, ptr %tag, ptr %rows, i32 %room, i32 %pp, i32 %pp, i8 %t45)
  br i1 %ok45, label %m45c, label %roomfault
m45c:
  %row45 = load i32, ptr %rows, align 4
  %rowid = sub i32 %row45, 1
  %ind45 = icmp slt i64 %len45, 0
  %l45 = trunc i64 %len45 to i32
  %dbl = shl i32 %l45, 1
  %cnt = select i1 %is4, i32 %l45, i32 %dbl
  %neg = icmp slt i32 %cnt, 0
  %cnt1 = select i1 %neg, i32 0, i32 %cnt
  %rem45 = select i1 %ind45, i32 0, i32 %cnt1
  %fl_i = select i1 %ind45, i32 1, i32 0
  %fl_m = select i1 %is4, i32 0, i32 2
  %fl45 = or i32 %fl_i, %fl_m
  %fpn = load i32, ptr %fp, align 4
  %fnz = zext i32 %fpn to i64
  %fn3 = mul i64 %fnz, 3
  %nb = getelementptr inbounds i32, ptr %work, i64 %fn3
  store i32 %rowid, ptr %nb, align 4
  %nbr = getelementptr inbounds i32, ptr %nb, i64 1
  store i32 %rem45, ptr %nbr, align 4
  %nbf = getelementptr inbounds i32, ptr %nb, i64 2
  store i32 %fl45, ptr %nbf, align 4
  %fpn1 = add i32 %fpn, 1
  store i32 %fpn1, ptr %fp, align 4
  store i32 %nx45, ptr %pos, align 4
  br label %top

m6:
  %r6 = call { i64, i32, i32 } @readlen(ptr %s, i32 %n, i32 %p1h, i32 %ai)
  %f6 = extractvalue { i64, i32, i32 } %r6, 2
  %bad6 = icmp ne i32 %f6, 0
  br i1 %bad6, label %rl_fault6, label %m6b
rl_fault6:
  %a6 = extractvalue { i64, i32, i32 } %r6, 1
  br label %rl_fault
m6b:
  %nx6 = extractvalue { i64, i32, i32 } %r6, 1
  store i32 %nx6, ptr %pos, align 4
  br label %parse

m7:
  %e20 = icmp eq i32 %ai, 20
  %e21 = icmp eq i32 %ai, 21
  %e24 = icmp eq i32 %ai, 24
  %e25 = icmp eq i32 %ai, 25
  %e26 = icmp eq i32 %ai, 26
  %e27 = icmp eq i32 %ai, 27
  %isbool = or i1 %e20, %e21
  %isdbl1 = or i1 %e25, %e26
  %isdbl = or i1 %isdbl1, %e27
  %t7a = select i1 %isdbl, i8 3, i8 0
  %t7 = select i1 %isbool, i8 1, i8 %t7a
  %z24 = select i1 %e24, i32 1, i32 0
  %z25 = select i1 %e25, i32 2, i32 %z24
  %z26 = select i1 %e26, i32 4, i32 %z25
  %sz7 = select i1 %e27, i32 8, i32 %z26
  %c7 = add i32 %pp, %sz7
  %ok7 = call i1 @add(ptr %span, ptr %tag, ptr %rows, i32 %room, i32 %pp, i32 %c7, i8 %t7)
  br i1 %ok7, label %m7b, label %roomfault
m7b:
  %np7a = add i32 %pp, 1
  %np7 = add i32 %np7a, %sz7
  store i32 %np7, ptr %pos, align 4
  br label %top

end:
  %total = load i32, ptr %rows, align 4
  %rend = call i32 @fin(ptr %flat, i32 %total, i32 %n, i32 0, i32 0, i32 0)
  ret i32 %rend

rl_fault:
  %rlf = phi i32 [ %f01, %rl_fault01 ], [ %f23, %rl_fault23 ], [ %f45, %rl_fault45 ], [ %f6, %rl_fault6 ]
  %rla = phi i32 [ %a01, %rl_fault01 ], [ %a23, %rl_fault23 ], [ %a45, %rl_fault45 ], [ %a6, %rl_fault6 ]
  %rlt = load i32, ptr %rows, align 4
  %rlr = call i32 @fin(ptr %flat, i32 %rlt, i32 %n, i32 %rlf, i32 %rla, i32 0)
  ret i32 %rlr
trunc_pc:
  %tct = load i32, ptr %rows, align 4
  %tcr = call i32 @fin(ptr %flat, i32 %tct, i32 %n, i32 1, i32 %pc, i32 0)
  ret i32 %tcr
trunc_np:
  %tnt = load i32, ptr %rows, align 4
  %tnr = call i32 @fin(ptr %flat, i32 %tnt, i32 %n, i32 1, i32 %np23, i32 0)
  ret i32 %tnr
trunc_pp:
  %tpt = load i32, ptr %rows, align 4
  %tpr = call i32 @fin(ptr %flat, i32 %tpt, i32 %n, i32 1, i32 %pp, i32 0)
  ret i32 %tpr
backward:
  %bwt = load i32, ptr %rows, align 4
  %bwr = call i32 @fin(ptr %flat, i32 %bwt, i32 %n, i32 4, i32 %pp, i32 0)
  ret i32 %bwr
roomfault:
  %rmt = load i32, ptr %rows, align 4
  %rmp = load i32, ptr %pos, align 4
  %rmr = call i32 @fin(ptr %flat, i32 %rmt, i32 %n, i32 5, i32 %rmp, i32 0)
  ret i32 %rmr
}

; ---- Syntax.YAML.scanYaml0 (with Yaml.kt's DirectYamlParser and the walk of scanYaml0), over the same Flat record
;
; Kotlin parses the text into a tree of nodes with line spans and walks it for rows.  The walk meets the elements in the order the parse starts
; them, so this scan makes the rows as it parses: a block container takes its row when it starts and its close when it ends.  The rows come out
; in the order of the walk, not of their opens (the children of an inline flow are placed from line 1), so buildTree's sort is asked for.
;
; A text whose first non-white byte is `{` or `[` goes to the JSON scanner, as in Kotlin.  The characters are the bytes sign-extended: a byte of
; 0x80 or more is a char of 0xFF80 or more, which no test below takes for white space or a digit.  White space is 9 to 13 and 28 to 32.
; Nothing is checked beyond what Kotlin checks.  The text of the parse is the lines of the text with \r\n, \r and \n taken as one break; the offsets
; of the rows are those of the text with every \r\n made \n (Kotlin's lineOffsets), so for a text with \r\n they differ from the byte offsets.
;
; Kotlin's parser has two quirks that this scan keeps because the rows show them.  The parser moves its line index by one after it has taken a
; line, though peekLine may have skipped blank lines to find it: the line is met again, once for every blank line before it.  And the hack for
; `- key: value` puts the rest of the line in a copy of the lines, at the line index before the increment, and parses on from there: a
; context of one line that is not the text (the line index, where the rest starts, its length, its indent).  When that line is a comment (the rest
; begins with #) the nested parse takes nothing, the index goes back, and Kotlin loops for ever: this scan stops with the fault Backward.  Kotlin puts the
; context back when the nested parse returns; this scan does not, because the line the context names is behind the line index by then and no peek meets it
; again (the one case where the index is on it is the loop above).
;
; Needs work of max(9 * (n + 1), 3 * room) words: the table of lines is 4 words a line (at most n + 1 lines), and a mapping, a sequence or an inline
; flow is a frame of 5 words in `work` (at most n + 1 frames; more is Kotlin calling itself for ever); nothing recurses.  The rows are not bounded by
; n (Kotlin reads a line again for every blank line before it), so the fault Room is the caller's signal to scan again with more room.

%yaml = type { [8 x ptr], [24 x i32] }
; ptr fields: 0 text, 1 table, 2 span, 3 tag, 4 frames
; i32 fields: 0 n, 1 L (lines), 2 room, 3 rows, 4 lineIdx, 5 ovl, 6 ovs, 7 ovt, 8 ovi (the context: line, start, length, indent), 9 rvend (the end
;             line of the node a parse returned), 10 sp (frames), 11 cap, 12 norm (the offset of the next line in the \r\n-less text), 13 fault
; a frame is 5 words: 0 where to go on (0 mapping, waiting for an entry; 1 mapping, waiting for a nested value; 2 sequence, waiting for an entry;
; 3 sequence, waiting for a nested entry; 4 an inline flow), 1 the indent (a flow: where its text begins), 2 the end line (a flow: its size), 3 its row
; (a flow: where to go on in its text), 4 a sequence: the line index before a nested `- key: value`, or -1 (a flow: 1 for a mapping)

define internal i32 @yget(ptr %Y, i32 %k) #1 {
  %p = getelementptr inbounds %yaml, ptr %Y, i32 0, i32 1, i32 %k
  %v = load i32, ptr %p, align 4
  ret i32 %v
}

define internal void @yset(ptr %Y, i32 %k, i32 %v) #1 {
  %p = getelementptr inbounds %yaml, ptr %Y, i32 0, i32 1, i32 %k
  store i32 %v, ptr %p, align 4
  ret void
}

define internal ptr @yptr(ptr %Y, i32 %k) #1 {
  %p = getelementptr inbounds %yaml, ptr %Y, i32 0, i32 0, i32 %k
  %v = load ptr, ptr %p, align 8
  ret ptr %v
}

define internal void @yptrset(ptr %Y, i32 %k, ptr %v) #1 {
  %p = getelementptr inbounds %yaml, ptr %Y, i32 0, i32 0, i32 %k
  store ptr %v, ptr %p, align 8
  ret void
}

; word k of a frame
define internal i32 @fget(ptr %fr, i32 %k) #1 {
  %kz = zext i32 %k to i64
  %p = getelementptr inbounds i32, ptr %fr, i64 %kz
  %v = load i32, ptr %p, align 4
  ret i32 %v
}

define internal void @fset(ptr %fr, i32 %k, i32 %v) #1 {
  %kz = zext i32 %k to i64
  %p = getelementptr inbounds i32, ptr %fr, i64 %kz
  store i32 %v, ptr %p, align 4
  ret void
}

; word k of line l0 (0-based) of the table: 0 start, 1 lead (leading white space), 2 tlen (length between the first and the last non-white
; byte), 3 the offset of the line in the text without \r\n
define internal i32 @tget(ptr %Y, i32 %l0, i32 %k) #1 {
  %tab = call ptr @yptr(ptr %Y, i32 1)
  %l4 = mul i32 %l0, 4
  %w = add i32 %l4, %k
  %wz = zext i32 %w to i64
  %p = getelementptr inbounds i32, ptr %tab, i64 %wz
  %v = load i32, ptr %p, align 4
  ret i32 %v
}

; Char.isWhitespace on the bytes: 9 to 13 and 28 to 32
define internal i1 @isws(i8 %c) #1 {
  %a = sub i8 %c, 9
  %b = icmp ule i8 %a, 4
  %d = sub i8 %c, 28
  %e = icmp ule i8 %d, 4
  %r = or i1 %b, %e
  ret i1 %r
}

; the index of the first byte that is not white space, or -1
define internal i32 @y_firstnonws(ptr %s, i32 %n) #0 {
entry:
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %i1, %next ]
  %c = icmp ult i32 %i, %n
  br i1 %c, label %body, label %none
body:
  %ch = call i8 @at8(ptr %s, i32 %i)
  %w = call i1 @isws(i8 %ch)
  br i1 %w, label %next, label %found
next:
  %i1 = add i32 %i, 1
  br label %loop
found:
  ret i32 %i
none:
  ret i32 -1
}

; one line (start, length) of the text into the table
define internal void @y_addline(ptr %Y, i32 %start, i32 %len) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  %tab = call ptr @yptr(ptr %Y, i32 1)
  %L = call i32 @yget(ptr %Y, i32 1)
  %norm = call i32 @yget(ptr %Y, i32 12)
  br label %lead
lead:
  %li = phi i32 [ 0, %entry ], [ %li1, %leadb ]
  %lc = icmp ult i32 %li, %len
  br i1 %lc, label %leadt, label %leaddone
leadt:
  %a = add i32 %start, %li
  %ch = call i8 @at8(ptr %s, i32 %a)
  %w = call i1 @isws(i8 %ch)
  br i1 %w, label %leadb, label %leaddone
leadb:
  %li1 = add i32 %li, 1
  br label %lead
leaddone:
  %ld = phi i32 [ %li, %lead ], [ %li, %leadt ]
  %blank = icmp eq i32 %ld, %len
  br i1 %blank, label %tl0, label %trail
trail:
  %te = phi i32 [ %len, %leaddone ], [ %te1, %trailb ]
  %tm = sub i32 %te, 1
  %tp = add i32 %start, %tm
  %tch = call i8 @at8(ptr %s, i32 %tp)
  %tw = call i1 @isws(i8 %tch)
  br i1 %tw, label %trailb, label %tldone
trailb:
  %te1 = sub i32 %te, 1
  br label %trail
tldone:
  %tlen = sub i32 %te, %ld
  br label %store
tl0:
  br label %store
store:
  %tl = phi i32 [ %tlen, %tldone ], [ 0, %tl0 ]
  %l4 = mul i32 %L, 4
  %w0 = zext i32 %l4 to i64
  %p0 = getelementptr inbounds i32, ptr %tab, i64 %w0
  store i32 %start, ptr %p0, align 4
  %p1 = getelementptr inbounds i32, ptr %p0, i64 1
  store i32 %ld, ptr %p1, align 4
  %p2 = getelementptr inbounds i32, ptr %p0, i64 2
  store i32 %tl, ptr %p2, align 4
  %p3 = getelementptr inbounds i32, ptr %p0, i64 3
  store i32 %norm, ptr %p3, align 4
  %L1 = add i32 %L, 1
  call void @yset(ptr %Y, i32 1, i32 %L1)
  %n1 = add i32 %len, 1
  %nn = add i32 %norm, %n1
  call void @yset(ptr %Y, i32 12, i32 %nn)
  ret void
}

; the lines of the text: a break is \n, \r or \r\n (Kotlin replaces \r\n by \n and \r by \n, then splits at \n)
define internal void @y_lines(ptr %Y) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  %n = call i32 @yget(ptr %Y, i32 0)
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %i2, %next ]
  %st = phi i32 [ 0, %entry ], [ %stn, %next ]
  %c = icmp ult i32 %i, %n
  br i1 %c, label %body, label %last
body:
  %ch = call i8 @at8(ptr %s, i32 %i)
  %is10 = icmp eq i8 %ch, 10
  %is13 = icmp eq i8 %ch, 13
  %isnl = or i1 %is10, %is13
  br i1 %isnl, label %brk, label %plain
plain:
  br label %next
brk:
  %len = sub i32 %i, %st
  call void @y_addline(ptr %Y, i32 %st, i32 %len)
  %nx = add i32 %i, 1
  %inb = icmp ult i32 %nx, %n
  %both = and i1 %is13, %inb
  br i1 %both, label %brk2, label %brk3
brk2:
  %nc = call i8 @at8(ptr %s, i32 %nx)
  %nl = icmp eq i8 %nc, 10
  br label %brk3
brk3:
  %skip = phi i1 [ false, %brk ], [ %nl, %brk2 ]
  %ib = select i1 %skip, i32 %nx, i32 %i
  %stb = add i32 %ib, 1
  br label %next
next:
  %ii = phi i32 [ %i, %plain ], [ %ib, %brk3 ]
  %stn = phi i32 [ %st, %plain ], [ %stb, %brk3 ]
  %i2 = add i32 %ii, 1
  br label %loop
last:
  %len2 = sub i32 %n, %st
  call void @y_addline(ptr %Y, i32 %st, i32 %len2)
  ret void
}

; getOffset: the offset of the start of line `line` (1 to L, always) in the text without \r\n.  (Kotlin guards a line outside 1 to L; there is none, and
; lineOffsets[L] is never asked for.)
define internal i32 @y_off(ptr %Y, i32 %line) #0 {
  %l0 = sub i32 %line, 1
  %v = call i32 @tget(ptr %Y, i32 %l0, i32 3)
  ret i32 %v
}

; getEndOffset: the offset of the last byte of line `line` (1 to L); for the last line the end of the text.  Kotlin takes the smaller of this and
; size - 1, and this is never the larger.
define internal i32 @y_endoff(ptr %Y, i32 %line) #0 {
entry:
  %L = call i32 @yget(ptr %Y, i32 1)
  %eq = icmp eq i32 %line, %L
  br i1 %eq, label %last, label %tab
tab:
  %v = call i32 @tget(ptr %Y, i32 %line, i32 3)
  %v1 = sub i32 %v, 1
  ret i32 %v1
last:
  %n = call i32 @yget(ptr %Y, i32 0)
  %nm = sub i32 %n, 1
  ret i32 %nm
}

; a row: 0, or 5 (Room) with the fault in Y
define internal i32 @y_emit(ptr %Y, i32 %open, i32 %close, i8 %t) #0 {
entry:
  %rows = call i32 @yget(ptr %Y, i32 3)
  %room = call i32 @yget(ptr %Y, i32 2)
  %full = icmp uge i32 %rows, %room
  br i1 %full, label %no, label %yes
yes:
  %span = call ptr @yptr(ptr %Y, i32 2)
  %tag = call ptr @yptr(ptr %Y, i32 3)
  call void @put(ptr %span, ptr %tag, i32 %rows, i32 %open, i32 %close, i8 %t)
  %r1 = add i32 %rows, 1
  call void @yset(ptr %Y, i32 3, i32 %r1)
  ret i32 0
no:
  call void @yset(ptr %Y, i32 13, i32 5)
  ret i32 5
}

; the row of a container node whose span is the single line `line`
define internal i32 @y_container(ptr %Y, i8 %t, i32 %line) #0 {
  %o = call i32 @y_off(ptr %Y, i32 %line)
  %e = call i32 @y_endoff(ptr %Y, i32 %line)
  %r = call i32 @y_emit(ptr %Y, i32 %o, i32 %e, i8 %t)
  ret i32 %r
}

; a frame of the parse: its address, or null (the fault Room)
define internal ptr @y_push(ptr %Y) #0 {
entry:
  %sp = call i32 @yget(ptr %Y, i32 10)
  %cap = call i32 @yget(ptr %Y, i32 11)
  %full = icmp uge i32 %sp, %cap
  br i1 %full, label %no, label %yes
yes:
  %frames = call ptr @yptr(ptr %Y, i32 4)
  %w = mul i32 %sp, 5
  %wz = zext i32 %w to i64
  %fr = getelementptr inbounds i32, ptr %frames, i64 %wz
  %sp1 = add i32 %sp, 1
  call void @yset(ptr %Y, i32 10, i32 %sp1)
  ret ptr %fr
no:
  ; n + 1 frames are more than any text can open: Kotlin's parse is then calling itself for ever (and overflows its stack)
  call void @yset(ptr %Y, i32 13, i32 4)
  ret ptr null
}

; the frame at index i
define internal ptr @y_frame(ptr %Y, i32 %i) #0 {
  %frames = call ptr @yptr(ptr %Y, i32 4)
  %w = mul i32 %i, 5
  %wz = zext i32 %w to i64
  %fr = getelementptr inbounds i32, ptr %frames, i64 %wz
  ret ptr %fr
}

; peekLine from lineIdx: out = { line number (1-based), indent, start of the trimmed content, its length }; false when there is none.  A blank
; line and a line whose content begins with # are skipped.  The context line (a copy of one line in Kotlin) stands for the line it replaces.
define internal i1 @y_peek(ptr %Y, ptr %o) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  %L = call i32 @yget(ptr %Y, i32 1)
  %idx0 = call i32 @yget(ptr %Y, i32 4)
  %ovl = call i32 @yget(ptr %Y, i32 5)
  br label %loop
loop:
  %idx = phi i32 [ %idx0, %entry ], [ %idx1, %skip ]
  %c = icmp slt i32 %idx, %L
  br i1 %c, label %body, label %none
body:
  %isov = icmp eq i32 %idx, %ovl
  br i1 %isov, label %ov, label %tabl
ov:
  %ovs = call i32 @yget(ptr %Y, i32 6)
  %ovt = call i32 @yget(ptr %Y, i32 7)
  %ovi = call i32 @yget(ptr %Y, i32 8)
  br label %chk
tabl:
  %start = call i32 @tget(ptr %Y, i32 %idx, i32 0)
  %lead = call i32 @tget(ptr %Y, i32 %idx, i32 1)
  %tlen = call i32 @tget(ptr %Y, i32 %idx, i32 2)
  %cst = add i32 %start, %lead
  br label %chk
chk:
  %cs = phi i32 [ %ovs, %ov ], [ %cst, %tabl ]
  %ct = phi i32 [ %ovt, %ov ], [ %tlen, %tabl ]
  %ind = phi i32 [ %ovi, %ov ], [ %lead, %tabl ]
  %empty = icmp eq i32 %ct, 0
  br i1 %empty, label %skip, label %hash
hash:
  %ch = call i8 @at8(ptr %s, i32 %cs)
  %ish = icmp eq i8 %ch, 35
  br i1 %ish, label %skip, label %found
skip:
  %idx1 = add i32 %idx, 1
  br label %loop
found:
  %ln = add i32 %idx, 1
  %o0 = getelementptr inbounds i32, ptr %o, i64 0
  store i32 %ln, ptr %o0, align 4
  %o1 = getelementptr inbounds i32, ptr %o, i64 1
  store i32 %ind, ptr %o1, align 4
  %o2 = getelementptr inbounds i32, ptr %o, i64 2
  store i32 %cs, ptr %o2, align 4
  %o3 = getelementptr inbounds i32, ptr %o, i64 3
  store i32 %ct, ptr %o3, align 4
  ret i1 true
none:
  ret i1 false
}

; findColon: the index of the first : outside quotes that is followed by white space or the end, or -1
define internal i32 @y_findcolon(ptr %Y, i32 %cs, i32 %ct) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %i1, %next ]
  %q = phi i32 [ 0, %entry ], [ %q2, %next ]
  %c = icmp slt i32 %i, %ct
  br i1 %c, label %body, label %none
body:
  %a = add i32 %cs, %i
  %ch = call i8 @at8(ptr %s, i32 %a)
  %chz = zext i8 %ch to i32
  %inq = icmp ne i32 %q, 0
  br i1 %inq, label %inquote, label %outq
inquote:
  %same = icmp eq i32 %chz, %q
  %qa = select i1 %same, i32 0, i32 %q
  br label %next
outq:
  %is34 = icmp eq i32 %chz, 34
  %is39 = icmp eq i32 %chz, 39
  %isq = or i1 %is34, %is39
  br i1 %isq, label %openq, label %notq
openq:
  br label %next
notq:
  %iscol = icmp eq i32 %chz, 58
  br i1 %iscol, label %colon, label %nonq
colon:
  %nx = add i32 %i, 1
  %atend = icmp eq i32 %nx, %ct
  br i1 %atend, label %hit, label %peekn
peekn:
  %na = add i32 %cs, %nx
  %nch = call i8 @at8(ptr %s, i32 %na)
  %nw = call i1 @isws(i8 %nch)
  br i1 %nw, label %hit, label %nonq
nonq:
  br label %next
hit:
  ret i32 %i
next:
  %q2 = phi i32 [ %qa, %inquote ], [ %chz, %openq ], [ %q, %nonq ]
  %i1 = add i32 %i, 1
  br label %loop
none:
  ret i32 -1
}

; String.trim() of the range [a, b)
define internal { i32, i32 } @y_trim(ptr %Y, i32 %a0, i32 %b0) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  br label %lt
lt:
  %a = phi i32 [ %a0, %entry ], [ %a1, %ltb ]
  %c = icmp slt i32 %a, %b0
  br i1 %c, label %ltt, label %rt
ltt:
  %ch = call i8 @at8(ptr %s, i32 %a)
  %w = call i1 @isws(i8 %ch)
  br i1 %w, label %ltb, label %rt
ltb:
  %a1 = add i32 %a, 1
  br label %lt
rt:
  %aa = phi i32 [ %a, %lt ], [ %a, %ltt ]
  br label %rl
rl:
  %b = phi i32 [ %b0, %rt ], [ %b1, %rlb ]
  %c2 = icmp sgt i32 %b, %aa
  br i1 %c2, label %rlt, label %done
rlt:
  %bm = sub i32 %b, 1
  %ch2 = call i8 @at8(ptr %s, i32 %bm)
  %w2 = call i1 @isws(i8 %ch2)
  br i1 %w2, label %rlb, label %done
rlb:
  %b1 = sub i32 %b, 1
  br label %rl
done:
  %bb = phi i32 [ %b, %rl ], [ %b, %rlt ]
  %r0 = insertvalue { i32, i32 } undef, i32 %aa, 0
  %r1 = insertvalue { i32, i32 } %r0, i32 %bb, 1
  ret { i32, i32 } %r1
}

; trimInlineWhitespace: only space and tab
define internal { i32, i32 } @y_trimi(ptr %Y, i32 %a0, i32 %b0) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  br label %lt
lt:
  %a = phi i32 [ %a0, %entry ], [ %a1, %ltb ]
  %c = icmp slt i32 %a, %b0
  br i1 %c, label %ltt, label %rt
ltt:
  %ch = call i8 @at8(ptr %s, i32 %a)
  %is32 = icmp eq i8 %ch, 32
  %is9 = icmp eq i8 %ch, 9
  %w = or i1 %is32, %is9
  br i1 %w, label %ltb, label %rt
ltb:
  %a1 = add i32 %a, 1
  br label %lt
rt:
  %aa = phi i32 [ %a, %lt ], [ %a, %ltt ]
  br label %rl
rl:
  %b = phi i32 [ %b0, %rt ], [ %b1, %rlb ]
  %c2 = icmp sgt i32 %b, %aa
  br i1 %c2, label %rlt, label %done
rlt:
  %bm = sub i32 %b, 1
  %ch2 = call i8 @at8(ptr %s, i32 %bm)
  %x32 = icmp eq i8 %ch2, 32
  %x9 = icmp eq i8 %ch2, 9
  %w2 = or i1 %x32, %x9
  br i1 %w2, label %rlb, label %done
rlb:
  %b1 = sub i32 %b, 1
  br label %rl
done:
  %bb = phi i32 [ %b, %rl ], [ %b, %rlt ]
  %r0 = insertvalue { i32, i32 } undef, i32 %aa, 0
  %r1 = insertvalue { i32, i32 } %r0, i32 %bb, 1
  ret { i32, i32 } %r1
}

; the index of the first byte `ch` in [a, b), or -1
define internal i32 @y_indexof(ptr %Y, i32 %a, i32 %b, i8 %ch) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  br label %loop
loop:
  %i = phi i32 [ %a, %entry ], [ %i1, %next ]
  %c = icmp slt i32 %i, %b
  br i1 %c, label %body, label %none
body:
  %x = call i8 @at8(ptr %s, i32 %i)
  %eq = icmp eq i8 %x, %ch
  br i1 %eq, label %hit, label %next
next:
  %i1 = add i32 %i, 1
  br label %loop
hit:
  ret i32 %i
none:
  ret i32 -1
}

; The states of String.toDoubleOrNull on one char c (a byte): 0 start, 1 sign, 2 "0", 3 digits, 4 ".", 5 digits.digits, 6 e, 29 e+ (Kotlin takes one more + there: 1e++5, not 1e+-5, 1e--5 or 1e+++5), 7 e sign, 8 e digits,
; 10 a suffix f F d D or white space after, 11 0x, 12 hex digits, 13 0x., 14 hex digits with a point, 15 p, 16 p sign, 17 p digits, 18 to 20 NaN,
; 21 to 28 Infinity, 31 no.  Accepting: 2 3 5 8 10 17 20 28.  (The regex of kotlin.text, checked on 245,459 texts against the real function.)
define internal i32 @nstep(i32 %st, i32 %c) #0 {
entry:
  %ctl = icmp ule i32 %c, 32
  %cd = sub i32 %c, 48
  %isd = icmp ule i32 %cd, 9
  %cl = or i32 %c, 32
  %la = sub i32 %cl, 97
  %hexl = icmp ule i32 %la, 5
  %ishex = or i1 %isd, %hexl
  %ise = icmp eq i32 %cl, 101
  %isd1 = icmp eq i32 %cl, 100
  %isf1 = icmp eq i32 %cl, 102
  %issuf = or i1 %isd1, %isf1
  %isp = icmp eq i32 %cl, 112
  %isx = icmp eq i32 %cl, 120
  %isp1 = icmp eq i32 %c, 43
  %ism1 = icmp eq i32 %c, 45
  %issign = or i1 %isp1, %ism1
  %isdot = icmp eq i32 %c, 46
  %is0 = icmp eq i32 %c, 48
  %isN = icmp eq i32 %c, 78
  %isI = icmp eq i32 %c, 73
  ; first(c)
  %f_i = select i1 %isI, i32 21, i32 31
  %f_n = select i1 %isN, i32 18, i32 %f_i
  %f_dot = select i1 %isdot, i32 4, i32 %f_n
  %f_d = select i1 %isd, i32 3, i32 %f_dot
  %f_0 = select i1 %is0, i32 2, i32 %f_d
  %first = select i1 %issign, i32 1, i32 %f_0
  switch i32 %st, label %rej [
    i32 0, label %s0
    i32 1, label %s1
    i32 2, label %s23
    i32 3, label %s23
    i32 4, label %s4
    i32 5, label %s5
    i32 6, label %s6
    i32 7, label %s7
    i32 8, label %s8
    i32 10, label %s9
    i32 11, label %s11
    i32 12, label %s12
    i32 13, label %s13
    i32 14, label %s14
    i32 15, label %s15
    i32 16, label %s16
    i32 17, label %s17
    i32 18, label %s18
    i32 19, label %s19
    i32 20, label %s20
    i32 21, label %s21
    i32 22, label %s22
    i32 23, label %s23c
    i32 24, label %s24
    i32 25, label %s25
    i32 26, label %s26
    i32 27, label %s27
    i32 28, label %s20
    i32 29, label %s29
  ]
rej:
  ret i32 31
s0:
  %r0 = select i1 %ctl, i32 0, i32 %first
  ret i32 %r0
s1:
  %bad1 = or i1 %ctl, %issign
  %r1 = select i1 %bad1, i32 31, i32 %first
  ret i32 %r1
s23:
  %t23a = select i1 %ctl, i32 10, i32 31
  %t23b = select i1 %issuf, i32 10, i32 %t23a
  %is2 = icmp eq i32 %st, 2
  %x2 = and i1 %is2, %isx
  %t23c = select i1 %x2, i32 11, i32 %t23b
  %t23d = select i1 %ise, i32 6, i32 %t23c
  %t23e = select i1 %isdot, i32 5, i32 %t23d
  %r23 = select i1 %isd, i32 3, i32 %t23e
  ret i32 %r23
s4:
  %r4 = select i1 %isd, i32 5, i32 31
  ret i32 %r4
s5:
  %t5a = select i1 %ctl, i32 10, i32 31
  %t5b = select i1 %issuf, i32 10, i32 %t5a
  %t5c = select i1 %ise, i32 6, i32 %t5b
  %r5 = select i1 %isd, i32 5, i32 %t5c
  ret i32 %r5
s6:
  %t6 = select i1 %isd, i32 8, i32 31
  %t6b = select i1 %ism1, i32 7, i32 %t6
  %r6 = select i1 %isp1, i32 29, i32 %t6b
  ret i32 %r6
s29:
  %t29 = select i1 %isd, i32 8, i32 31
  %r29 = select i1 %isp1, i32 7, i32 %t29
  ret i32 %r29
s7:
  %r7 = select i1 %isd, i32 8, i32 31
  ret i32 %r7
s8:
  %t8a = select i1 %ctl, i32 10, i32 31
  %t8b = select i1 %issuf, i32 10, i32 %t8a
  %r8 = select i1 %isd, i32 8, i32 %t8b
  ret i32 %r8
s9:
  %r9 = select i1 %ctl, i32 10, i32 31
  ret i32 %r9
s11:
  %t11 = select i1 %isdot, i32 13, i32 31
  %r11 = select i1 %ishex, i32 12, i32 %t11
  ret i32 %r11
s12:
  %t12a = select i1 %isp, i32 15, i32 31
  %t12b = select i1 %isdot, i32 14, i32 %t12a
  %r12 = select i1 %ishex, i32 12, i32 %t12b
  ret i32 %r12
s13:
  %r13 = select i1 %ishex, i32 14, i32 31
  ret i32 %r13
s14:
  %t14 = select i1 %isp, i32 15, i32 31
  %r14 = select i1 %ishex, i32 14, i32 %t14
  ret i32 %r14
s15:
  %t15 = select i1 %isd, i32 17, i32 31
  %r15 = select i1 %issign, i32 16, i32 %t15
  ret i32 %r15
s16:
  %r16 = select i1 %isd, i32 17, i32 31
  ret i32 %r16
s17:
  %t17a = select i1 %ctl, i32 10, i32 31
  %t17b = select i1 %issuf, i32 10, i32 %t17a
  %r17 = select i1 %isd, i32 17, i32 %t17b
  ret i32 %r17
s18:
  %e18 = icmp eq i32 %c, 97
  %r18 = select i1 %e18, i32 19, i32 31
  ret i32 %r18
s19:
  %r19 = select i1 %isN, i32 20, i32 31
  ret i32 %r19
s20:
  %r20 = select i1 %ctl, i32 10, i32 31
  ret i32 %r20
s21:
  %e21 = icmp eq i32 %c, 110
  %r21 = select i1 %e21, i32 22, i32 31
  ret i32 %r21
s22:
  %e22 = icmp eq i32 %c, 102
  %r22 = select i1 %e22, i32 23, i32 31
  ret i32 %r22
s23c:
  %e23 = icmp eq i32 %c, 105
  %r23c = select i1 %e23, i32 24, i32 31
  ret i32 %r23c
s24:
  %e24 = icmp eq i32 %c, 110
  %r24 = select i1 %e24, i32 25, i32 31
  ret i32 %r24
s25:
  %e25 = icmp eq i32 %c, 105
  %r25 = select i1 %e25, i32 26, i32 31
  ret i32 %r25
s26:
  %e26 = icmp eq i32 %c, 116
  %r26 = select i1 %e26, i32 27, i32 31
  ret i32 %r26
s27:
  %e27 = icmp eq i32 %c, 121
  %r27 = select i1 %e27, i32 28, i32 31
  ret i32 %r27
}

; the states of the text being exactly true or false: 0 start, 1 t, 2 tr, 3 tru, 4 true, 5 f, 6 fa, 7 fal, 8 fals, 9 false, 31 no
define internal i32 @bstep(i32 %st, i32 %c) #0 {
entry:
  switch i32 %st, label %rej [
    i32 0, label %b0
    i32 1, label %b1
    i32 2, label %b2
    i32 3, label %b3
    i32 5, label %b5
    i32 6, label %b6
    i32 7, label %b7
    i32 8, label %b8
  ]
rej:
  ret i32 31
b0:
  %ist = icmp eq i32 %c, 116
  %isf = icmp eq i32 %c, 102
  %r0a = select i1 %isf, i32 5, i32 31
  %r0 = select i1 %ist, i32 1, i32 %r0a
  ret i32 %r0
b1:
  %e1 = icmp eq i32 %c, 114
  %r1 = select i1 %e1, i32 2, i32 31
  ret i32 %r1
b2:
  %e2 = icmp eq i32 %c, 117
  %r2 = select i1 %e2, i32 3, i32 31
  ret i32 %r2
b3:
  %e3 = icmp eq i32 %c, 101
  %r3 = select i1 %e3, i32 4, i32 31
  ret i32 %r3
b5:
  %e5 = icmp eq i32 %c, 97
  %r5 = select i1 %e5, i32 6, i32 31
  ret i32 %r5
b6:
  %e6 = icmp eq i32 %c, 108
  %r6 = select i1 %e6, i32 7, i32 31
  ret i32 %r6
b7:
  %e7 = icmp eq i32 %c, 115
  %r7 = select i1 %e7, i32 8, i32 31
  ret i32 %r7
b8:
  %e8 = icmp eq i32 %c, 101
  %r8 = select i1 %e8, i32 9, i32 31
  ret i32 %r8
}

; the two states after the bytes [a, b) of the text
define internal { i32, i32 } @y_feed(ptr %Y, i32 %a, i32 %b, i32 %ns0, i32 %bs0) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  br label %loop
loop:
  %i = phi i32 [ %a, %entry ], [ %i1, %body ]
  %ns = phi i32 [ %ns0, %entry ], [ %ns1, %body ]
  %bs = phi i32 [ %bs0, %entry ], [ %bs1, %body ]
  %c = icmp slt i32 %i, %b
  br i1 %c, label %body, label %done
body:
  %ch = call i8 @at8(ptr %s, i32 %i)
  %chz = zext i8 %ch to i32
  %ns1 = call i32 @nstep(i32 %ns, i32 %chz)
  %bs1 = call i32 @bstep(i32 %bs, i32 %chz)
  %i1 = add i32 %i, 1
  br label %loop
done:
  %r0 = insertvalue { i32, i32 } undef, i32 %ns, 0
  %r1 = insertvalue { i32, i32 } %r0, i32 %bs, 1
  ret { i32, i32 } %r1
}

; 1 when the text so far is a number (toDoubleOrNull is not null), 2 when it is true or false, else 3 (a string)
define internal i32 @y_kind(i32 %ns, i32 %bs) #1 {
  %sh = shl i32 1, %ns
  %m = and i32 %sh, 269616428
  %isnum = icmp ne i32 %m, 0
  %b4 = icmp eq i32 %bs, 4
  %b9 = icmp eq i32 %bs, 9
  %isb = or i1 %b4, %b9
  %k2 = select i1 %isb, i32 2, i32 3
  %k = select i1 %isnum, i32 1, i32 %k2
  ret i32 %k
}

; the kind of the text [a, b)
define internal i32 @y_classify(ptr %Y, i32 %a, i32 %b) #0 {
  %r = call { i32, i32 } @y_feed(ptr %Y, i32 %a, i32 %b, i32 0, i32 0)
  %ns = extractvalue { i32, i32 } %r, 0
  %bs = extractvalue { i32, i32 } %r, 1
  %k = call i32 @y_kind(i32 %ns, i32 %bs)
  ret i32 %k
}

; The row of a scalar node with the span lines spanA to spanB (both in 1 to L): kind 0 no value (Nothing), 1 a number (Double), 2 true or false
; (Boolean), 3 a string.  The row begins at the start of the first line's text and ends at the end of the last line, white space cut off; a string
; takes a quote on either side.  0, or 5 (Room).  (Kotlin reads lines[spanA - 1] and the byte before the start of the text; neither can be out
; of range, so there is no fault but Room.)
define internal i32 @y_scalar_row(ptr %Y, i32 %spanA, i32 %spanB, i32 %kind) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  %vo = call i32 @y_off(ptr %Y, i32 %spanA)
  %eo = call i32 @y_endoff(ptr %Y, i32 %spanB)
  %l0 = sub i32 %spanA, 1
  %lead = call i32 @tget(ptr %Y, i32 %l0, i32 1)
  %vstart = add i32 %vo, %lead
  br label %cut
cut:
  %vend = phi i32 [ %eo, %entry ], [ %vend1, %cutb ]
  %cc = icmp sge i32 %vend, %vstart
  br i1 %cc, label %cutt, label %cutdone
cutt:
  %ch = call i8 @at8(ptr %s, i32 %vend)
  %w = call i1 @isws(i8 %ch)
  br i1 %w, label %cutb, label %cutdone
cutb:
  %vend1 = sub i32 %vend, 1
  br label %cut
cutdone:
  %ve = phi i32 [ %vend, %cut ], [ %vend, %cutt ]
  switch i32 %kind, label %str [
    i32 0, label %nothing
    i32 1, label %dbl
    i32 2, label %bool
  ]
nothing:
  %rn = call i32 @y_emit(ptr %Y, i32 %vstart, i32 %ve, i8 0)
  ret i32 %rn
dbl:
  %rd = call i32 @y_emit(ptr %Y, i32 %vstart, i32 %ve, i8 3)
  ret i32 %rd
bool:
  %rb = call i32 @y_emit(ptr %Y, i32 %vstart, i32 %ve, i8 1)
  ret i32 %rb
str:
  %vs = call i32 @y_quote_before(ptr %Y, i32 %vstart)
  %ve2 = call i32 @y_quote_after(ptr %Y, i32 %ve)
  %rs = call i32 @y_emit(ptr %Y, i32 %vs, i32 %ve2, i8 4)
  ret i32 %rs
}

; a start moved to the quote before it, if the byte before it is a "
define internal i32 @y_quote_before(ptr %Y, i32 %start) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  %pos = icmp sgt i32 %start, 0
  br i1 %pos, label %look, label %keep
look:
  %pi = sub i32 %start, 1
  %pc = call i8 @at8(ptr %s, i32 %pi)
  %isq = icmp eq i8 %pc, 34
  %r = select i1 %isq, i32 %pi, i32 %start
  ret i32 %r
keep:
  ret i32 %start
}

; an end moved to the quote after it, if the byte after it is a "
define internal i32 @y_quote_after(ptr %Y, i32 %end) #0 {
entry:
  %s = call ptr @yptr(ptr %Y, i32 0)
  %n = call i32 @yget(ptr %Y, i32 0)
  %e1 = add i32 %end, 1
  %inb = icmp slt i32 %e1, %n
  br i1 %inb, label %look, label %keep
look:
  %ec = call i8 @at8(ptr %s, i32 %e1)
  %isq = icmp eq i8 %ec, 34
  %r = select i1 %isq, i32 %e1, i32 %end
  ret i32 %r
keep:
  ret i32 %end
}

; the row of the key of an entry whose span begins at line spanA (in 1 to L), keyLen chars long (a quote on either side belongs to it)
define internal i32 @y_key_row(ptr %Y, i32 %spanA, i32 %keyLen) #0 {
entry:
  %ko = call i32 @y_off(ptr %Y, i32 %spanA)
  %l0 = sub i32 %spanA, 1
  %lead = call i32 @tget(ptr %Y, i32 %l0, i32 1)
  %kstart = add i32 %ko, %lead
  %kc0 = add i32 %kstart, %keyLen
  %kclose = sub i32 %kc0, 1
  %ks = call i32 @y_quote_before(ptr %Y, i32 %kstart)
  %ke = call i32 @y_quote_after(ptr %Y, i32 %kclose)
  %rk = call i32 @y_emit(ptr %Y, i32 %ks, i32 %ke, i8 4)
  ret i32 %rk
}

define i32 @confix_scan_yaml(ptr %cs, ptr %flat) #0 {
entry:
  %n = load i32, ptr %cs, align 8
  %dp = getelementptr inbounds %confix.chars, ptr %cs, i32 0, i32 1
  %s = load ptr, ptr %dp, align 8
  %rp = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 3
  %room = load i32, ptr %rp, align 4
  %p4 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 4
  %span = load ptr, ptr %p4, align 8
  %p5 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 5
  %tag = load ptr, ptr %p5, align 8
  %p9 = getelementptr inbounds %confix.flat, ptr %flat, i32 0, i32 9
  %work = load ptr, ptr %p9, align 8
  %Y = alloca %yaml, align 8
  %pk = alloca [4 x i32], align 4
  %v_ln = alloca i32, align 4
  %v_a = alloca i32, align 4
  %v_b = alloca i32, align 4
  %pi_a = alloca i32, align 4
  %pi_size = alloca i32, align 4
  %pi_map = alloca i32, align 4
  %pi_line = alloca i32, align 4
  %t_a = alloca i32, align 4
  %t_b = alloca i32, align 4
  %k_a = alloca i32, align 4
  %k_b = alloca i32, align 4
  %a_ns = alloca i32, align 4
  %a_bs = alloca i32, align 4
  %a_end = alloca i32, align 4
  store %yaml zeroinitializer, ptr %Y, align 8
  call void @yptrset(ptr %Y, i32 0, ptr %s)
  call void @yptrset(ptr %Y, i32 2, ptr %span)
  call void @yptrset(ptr %Y, i32 3, ptr %tag)
  call void @yset(ptr %Y, i32 0, i32 %n)
  call void @yset(ptr %Y, i32 2, i32 %room)
  call void @yset(ptr %Y, i32 5, i32 -1)
  %fnw = call i32 @y_firstnonws(ptr %s, i32 %n)
  %hasnw = icmp sge i32 %fnw, 0
  br i1 %hasnw, label %chkj, label %noj
chkj:
  %fch = call i8 @at8(ptr %s, i32 %fnw)
  %isbrace = icmp eq i8 %fch, 123
  %isbrack = icmp eq i8 %fch, 91
  %isjson = or i1 %isbrace, %isbrack
  br i1 %isjson, label %json, label %noj
json:
  %rj = call i32 @confix_scan_json(ptr %cs, ptr %flat)
  ret i32 %rj
noj:
  %n1 = add i32 %n, 1
  br label %setup
setup:
  call void @yptrset(ptr %Y, i32 1, ptr %work)
  %n1z = zext i32 %n1 to i64
  %tw = mul i64 %n1z, 4
  %frames = getelementptr inbounds i32, ptr %work, i64 %tw
  call void @yptrset(ptr %Y, i32 4, ptr %frames)
  call void @yset(ptr %Y, i32 11, i32 %n1)
  call void @y_lines(ptr %Y)
  br label %block

; ---- parseBlock: look at the next line and begin the node it is
block:
  %bf = call i1 @y_peek(ptr %Y, ptr %pk)
  br i1 %bf, label %bhave, label %bnone
bnone:
  %bL = call i32 @yget(ptr %Y, i32 1)
  %bst = call i32 @y_scalar_row(ptr %Y, i32 %bL, i32 %bL, i32 0)
  %bbad = icmp ne i32 %bst, 0
  br i1 %bbad, label %fail, label %bnone2
bnone2:
  br label %resume
bhave:
  %bk0 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 0
  %bln = load i32, ptr %bk0, align 4
  %bk1 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 1
  %bind = load i32, ptr %bk1, align 4
  %bk2 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 2
  %bcs = load i32, ptr %bk2, align 4
  %bk3 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 3
  %bct = load i32, ptr %bk3, align 4
  %bc0 = call i8 @at8(ptr %s, i32 %bcs)
  %bdash = icmp eq i8 %bc0, 45
  br i1 %bdash, label %pseq, label %bmap
bmap:
  %bcol = call i32 @y_findcolon(ptr %Y, i32 %bcs, i32 %bct)
  %bhas = icmp sge i32 %bcol, 0
  br i1 %bhas, label %pmap, label %pscal

pseq:
  %sli = call i32 @yget(ptr %Y, i32 4)
  %sline = add i32 %sli, 1
  %sst = call i32 @y_container(ptr %Y, i8 6, i32 %sline)
  %sbad = icmp ne i32 %sst, 0
  br i1 %sbad, label %fail, label %pseq2
pseq2:
  %sfr = call ptr @y_push(ptr %Y)
  %snull = icmp eq ptr %sfr, null
  br i1 %snull, label %fail, label %pseq3
pseq3:
  %srows = call i32 @yget(ptr %Y, i32 3)
  %srow = sub i32 %srows, 1
  call void @fset(ptr %sfr, i32 0, i32 2)
  call void @fset(ptr %sfr, i32 1, i32 %bind)
  call void @fset(ptr %sfr, i32 3, i32 %srow)
  br label %resume

pmap:
  %mli = call i32 @yget(ptr %Y, i32 4)
  %mline = add i32 %mli, 1
  %mst = call i32 @y_container(ptr %Y, i8 5, i32 %mline)
  %mbad = icmp ne i32 %mst, 0
  br i1 %mbad, label %fail, label %pmap2
pmap2:
  %mfr = call ptr @y_push(ptr %Y)
  %mnull = icmp eq ptr %mfr, null
  br i1 %mnull, label %fail, label %pmap3
pmap3:
  %mrows = call i32 @yget(ptr %Y, i32 3)
  %mrow = sub i32 %mrows, 1
  call void @fset(ptr %mfr, i32 0, i32 0)
  call void @fset(ptr %mfr, i32 1, i32 %bind)
  call void @fset(ptr %mfr, i32 3, i32 %mrow)
  br label %resume

; ---- parseScalar: the line, and the more deeply indented lines that follow without a colon, joined by single spaces
pscal:
  %qli = call i32 @yget(ptr %Y, i32 4)
  %qstart = add i32 %qli, 1
  call void @yset(ptr %Y, i32 4, i32 %qstart)
  store i32 %bln, ptr %a_end, align 4
  %qe0 = add i32 %bcs, %bct
  %qf = call { i32, i32 } @y_feed(ptr %Y, i32 %bcs, i32 %qe0, i32 0, i32 0)
  %qns0 = extractvalue { i32, i32 } %qf, 0
  %qbs0 = extractvalue { i32, i32 } %qf, 1
  store i32 %qns0, ptr %a_ns, align 4
  store i32 %qbs0, ptr %a_bs, align 4
  br label %qloop
qloop:
  %qp = call i1 @y_peek(ptr %Y, ptr %pk)
  br i1 %qp, label %qhave, label %qdone
qhave:
  %qk0 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 0
  %qln = load i32, ptr %qk0, align 4
  %qk1 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 1
  %qind = load i32, ptr %qk1, align 4
  %qk2 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 2
  %qcs = load i32, ptr %qk2, align 4
  %qk3 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 3
  %qct = load i32, ptr %qk3, align 4
  %qle = icmp sle i32 %qind, %bind
  br i1 %qle, label %qdone, label %qcol
qcol:
  %qcc = call i32 @y_findcolon(ptr %Y, i32 %qcs, i32 %qct)
  %qhas = icmp sge i32 %qcc, 0
  br i1 %qhas, label %qdone, label %qtake
qtake:
  %qli2 = call i32 @yget(ptr %Y, i32 4)
  %qli3 = add i32 %qli2, 1
  call void @yset(ptr %Y, i32 4, i32 %qli3)
  store i32 %qln, ptr %a_end, align 4
  %qns = load i32, ptr %a_ns, align 4
  %qns1 = call i32 @nstep(i32 %qns, i32 32)
  %qe1 = add i32 %qcs, %qct
  %qf2 = call { i32, i32 } @y_feed(ptr %Y, i32 %qcs, i32 %qe1, i32 %qns1, i32 31)
  %qns2 = extractvalue { i32, i32 } %qf2, 0
  store i32 %qns2, ptr %a_ns, align 4
  store i32 31, ptr %a_bs, align 4
  br label %qloop
qdone:
  %qdns = load i32, ptr %a_ns, align 4
  %qdbs = load i32, ptr %a_bs, align 4
  %qkind = call i32 @y_kind(i32 %qdns, i32 %qdbs)
  %qend = load i32, ptr %a_end, align 4
  %qst = call i32 @y_scalar_row(ptr %Y, i32 %qstart, i32 %qend, i32 %qkind)
  %qbad = icmp ne i32 %qst, 0
  br i1 %qbad, label %fail, label %qdone2
qdone2:
  call void @yset(ptr %Y, i32 9, i32 %qend)
  br label %resume

; ---- return to the node that is waiting (the frame on top), or finish
resume:
  %rsp = call i32 @yget(ptr %Y, i32 10)
  %rz = icmp eq i32 %rsp, 0
  br i1 %rz, label %finish, label %rdisp
rdisp:
  %rsm = sub i32 %rsp, 1
  %fr = call ptr @y_frame(ptr %Y, i32 %rsm)
  %rpc = call i32 @fget(ptr %fr, i32 0)
  %risinl = icmp eq i32 %rpc, 4
  br i1 %risinl, label %inl_run, label %rctx
rctx:
  switch i32 %rpc, label %seq_after [
    i32 0, label %map_loop
    i32 1, label %map_after
    i32 2, label %seq_loop
  ]

; ---- parseMapping
map_loop:
  %mindent = call i32 @fget(ptr %fr, i32 1)
  %mpk = call i1 @y_peek(ptr %Y, ptr %pk)
  br i1 %mpk, label %map_have, label %map_finish
map_have:
  %mk0 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 0
  %mln = load i32, ptr %mk0, align 4
  %mk1 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 1
  %mind = load i32, ptr %mk1, align 4
  %mk2 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 2
  %mcs = load i32, ptr %mk2, align 4
  %mk3 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 3
  %mct = load i32, ptr %mk3, align 4
  %mless = icmp slt i32 %mind, %mindent
  br i1 %mless, label %map_finish, label %map_c2
map_c2:
  %mmore = icmp sgt i32 %mind, %mindent
  br i1 %mmore, label %map_skip, label %map_c3
map_skip:
  %msl = call i32 @yget(ptr %Y, i32 4)
  %msl1 = add i32 %msl, 1
  call void @yset(ptr %Y, i32 4, i32 %msl1)
  call void @fset(ptr %fr, i32 2, i32 %mln)
  br label %map_loop
map_c3:
  %mcol = call i32 @y_findcolon(ptr %Y, i32 %mcs, i32 %mct)
  %mhas = icmp sge i32 %mcol, 0
  br i1 %mhas, label %map_ent, label %map_finish
map_ent:
  %mel = call i32 @yget(ptr %Y, i32 4)
  %mel1 = add i32 %mel, 1
  call void @yset(ptr %Y, i32 4, i32 %mel1)
  call void @fset(ptr %fr, i32 2, i32 %mln)
  %mke = add i32 %mcs, %mcol
  %mkr = call { i32, i32 } @y_trim(ptr %Y, i32 %mcs, i32 %mke)
  %mka = extractvalue { i32, i32 } %mkr, 0
  %mkb = extractvalue { i32, i32 } %mkr, 1
  %mva0 = add i32 %mke, 1
  %mve0 = add i32 %mcs, %mct
  %mvr = call { i32, i32 } @y_trim(ptr %Y, i32 %mva0, i32 %mve0)
  %mva = extractvalue { i32, i32 } %mvr, 0
  %mvb = extractvalue { i32, i32 } %mvr, 1
  %mklen = sub i32 %mkb, %mka
  %mkst = call i32 @y_key_row(ptr %Y, i32 %mln, i32 %mklen)
  %mkbad = icmp ne i32 %mkst, 0
  br i1 %mkbad, label %fail, label %map_ent2
map_ent2:
  %mhasv = icmp sgt i32 %mvb, %mva
  br i1 %mhasv, label %map_val, label %map_noval
map_val:
  store i32 %mln, ptr %v_ln, align 4
  store i32 %mva, ptr %v_a, align 4
  store i32 %mvb, ptr %v_b, align 4
  br label %value_at
map_noval:
  %mnp = call i1 @y_peek(ptr %Y, ptr %pk)
  br i1 %mnp, label %map_nv2, label %map_nullv
map_nv2:
  %mnk1 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 1
  %mnind = load i32, ptr %mnk1, align 4
  %mndeep = icmp sgt i32 %mnind, %mindent
  br i1 %mndeep, label %map_block, label %map_nullv
map_block:
  call void @fset(ptr %fr, i32 0, i32 1)
  br label %block
map_nullv:
  %mnst = call i32 @y_scalar_row(ptr %Y, i32 %mln, i32 %mln, i32 0)
  %mnbad = icmp ne i32 %mnst, 0
  br i1 %mnbad, label %fail, label %resume
map_after:
  %mrv = call i32 @yget(ptr %Y, i32 9)
  call void @fset(ptr %fr, i32 2, i32 %mrv)
  call void @fset(ptr %fr, i32 0, i32 0)
  br label %map_loop
map_finish:
  %mfend = call i32 @fget(ptr %fr, i32 2)
  %mfrow = call i32 @fget(ptr %fr, i32 3)
  %mfeo = call i32 @y_endoff(ptr %Y, i32 %mfend)
  call void @setclose(ptr %span, i32 %mfrow, i32 %mfeo)
  call void @yset(ptr %Y, i32 9, i32 %mfend)
  %mfsp = call i32 @yget(ptr %Y, i32 10)
  %mfsp1 = sub i32 %mfsp, 1
  call void @yset(ptr %Y, i32 10, i32 %mfsp1)
  br label %resume

; ---- parseSequence
seq_loop:
  %sindent = call i32 @fget(ptr %fr, i32 1)
  %spk = call i1 @y_peek(ptr %Y, ptr %pk)
  br i1 %spk, label %seq_have, label %seq_finish
seq_have:
  %sk0 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 0
  %sln = load i32, ptr %sk0, align 4
  %sk1 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 1
  %sind = load i32, ptr %sk1, align 4
  %sk2 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 2
  %scs = load i32, ptr %sk2, align 4
  %sk3 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 3
  %sct = load i32, ptr %sk3, align 4
  %sless = icmp slt i32 %sind, %sindent
  br i1 %sless, label %seq_finish, label %seq_c2
seq_c2:
  %sc0 = call i8 @at8(ptr %s, i32 %scs)
  %sdash = icmp eq i8 %sc0, 45
  br i1 %sdash, label %seq_ent, label %seq_finish
seq_ent:
  %sel = call i32 @yget(ptr %Y, i32 4)
  %sel1 = add i32 %sel, 1
  call void @yset(ptr %Y, i32 4, i32 %sel1)
  call void @fset(ptr %fr, i32 2, i32 %sln)
  %sra0 = add i32 %scs, 1
  %sre0 = add i32 %scs, %sct
  %srr = call { i32, i32 } @y_trim(ptr %Y, i32 %sra0, i32 %sre0)
  %sra = extractvalue { i32, i32 } %srr, 0
  %srb = extractvalue { i32, i32 } %srr, 1
  %shasrest = icmp sgt i32 %srb, %sra
  br i1 %shasrest, label %seq_rest, label %seq_empty
seq_rest:
  %srlen = sub i32 %srb, %sra
  %srcol = call i32 @y_findcolon(ptr %Y, i32 %sra, i32 %srlen)
  %srhas = icmp sge i32 %srcol, 0
  br i1 %srhas, label %seq_nested, label %seq_val
seq_val:
  store i32 %sln, ptr %v_ln, align 4
  store i32 %sra, ptr %v_a, align 4
  store i32 %srb, ptr %v_b, align 4
  br label %value_at
seq_nested:
  %snsaved = call i32 @yget(ptr %Y, i32 4)
  %snx = sub i32 %snsaved, 1
  call void @fset(ptr %fr, i32 4, i32 %snx)
  call void @yset(ptr %Y, i32 4, i32 %snx)
  call void @yset(ptr %Y, i32 5, i32 %snx)
  call void @yset(ptr %Y, i32 6, i32 %sra)
  call void @yset(ptr %Y, i32 7, i32 %srlen)
  %snind = add i32 %sindent, 2
  call void @yset(ptr %Y, i32 8, i32 %snind)
  call void @fset(ptr %fr, i32 0, i32 3)
  br label %block
seq_empty:
  %sep = call i1 @y_peek(ptr %Y, ptr %pk)
  br i1 %sep, label %seq_e2, label %seq_nullv
seq_e2:
  %sek1 = getelementptr inbounds [4 x i32], ptr %pk, i32 0, i32 1
  %seind = load i32, ptr %sek1, align 4
  %sedeep = icmp sgt i32 %seind, %sindent
  br i1 %sedeep, label %seq_block, label %seq_nullv
seq_block:
  call void @fset(ptr %fr, i32 4, i32 -1)
  call void @fset(ptr %fr, i32 0, i32 3)
  br label %block
seq_nullv:
  %snst = call i32 @y_scalar_row(ptr %Y, i32 %sln, i32 %sln, i32 0)
  %snbad = icmp ne i32 %snst, 0
  br i1 %snbad, label %fail, label %resume
seq_after:
  %sarv = call i32 @yget(ptr %Y, i32 9)
  call void @fset(ptr %fr, i32 2, i32 %sarv)
  %saX = call i32 @fget(ptr %fr, i32 4)
  %saov = icmp sge i32 %saX, 0
  %salidx = call i32 @yget(ptr %Y, i32 4)
  %sasame = icmp eq i32 %salidx, %saX
  %sabw = and i1 %saov, %sasame
  br i1 %sabw, label %backward, label %seq_after2
seq_after2:
  call void @fset(ptr %fr, i32 0, i32 2)
  br label %seq_loop
seq_finish:
  %sfend = call i32 @fget(ptr %fr, i32 2)
  %sfrow = call i32 @fget(ptr %fr, i32 3)
  %sfeo = call i32 @y_endoff(ptr %Y, i32 %sfend)
  call void @setclose(ptr %span, i32 %sfrow, i32 %sfeo)
  call void @yset(ptr %Y, i32 9, i32 %sfend)
  %sfsp = call i32 @yget(ptr %Y, i32 10)
  %sfsp1 = sub i32 %sfsp, 1
  call void @yset(ptr %Y, i32 10, i32 %sfsp1)
  br label %resume

; ---- the value text [v_a, v_b) of the line v_ln: an inline sequence or mapping, or a scalar
value_at:
  %vln = load i32, ptr %v_ln, align 4
  %va = load i32, ptr %v_a, align 4
  %vb = load i32, ptr %v_b, align 4
  %vc0 = call i8 @at8(ptr %s, i32 %va)
  %visb = icmp eq i8 %vc0, 91
  %visc = icmp eq i8 %vc0, 123
  %visfl = or i1 %visb, %visc
  br i1 %visfl, label %value_flow, label %value_scalar
value_flow:
  %vsz = sub i32 %vb, %va
  store i32 %va, ptr %pi_a, align 4
  store i32 %vsz, ptr %pi_size, align 4
  %vmap = zext i1 %visc to i32
  store i32 %vmap, ptr %pi_map, align 4
  store i32 %vln, ptr %pi_line, align 4
  br label %push_inl
value_scalar:
  %vkind = call i32 @y_classify(ptr %Y, i32 %va, i32 %vb)
  %vst = call i32 @y_scalar_row(ptr %Y, i32 %vln, i32 %vln, i32 %vkind)
  %vbad = icmp ne i32 %vst, 0
  br i1 %vbad, label %fail, label %resume

; ---- an inline sequence or mapping begins: its row, and a frame
push_inl:
  %pia = load i32, ptr %pi_a, align 4
  %pisz = load i32, ptr %pi_size, align 4
  %pimap = load i32, ptr %pi_map, align 4
  %piline = load i32, ptr %pi_line, align 4
  %pismap = icmp ne i32 %pimap, 0
  %pitag = select i1 %pismap, i8 5, i8 6
  %pist = call i32 @y_container(ptr %Y, i8 %pitag, i32 %piline)
  %pibad = icmp ne i32 %pist, 0
  br i1 %pibad, label %fail, label %push_inl2
push_inl2:
  %pifr = call ptr @y_push(ptr %Y)
  %pinull = icmp eq ptr %pifr, null
  br i1 %pinull, label %fail, label %push_inl3
push_inl3:
  call void @fset(ptr %pifr, i32 0, i32 4)
  call void @fset(ptr %pifr, i32 1, i32 %pia)
  call void @fset(ptr %pifr, i32 2, i32 %pisz)
  call void @fset(ptr %pifr, i32 3, i32 1)
  call void @fset(ptr %pifr, i32 4, i32 %pimap)
  br label %resume

; ---- parseInlineSequence / parseInlineMapping: cut the text [1, size - 1] at the commas outside brackets
inl_run:
  %iva = call i32 @fget(ptr %fr, i32 1)
  %isz = call i32 @fget(ptr %fr, i32 2)
  %ii0 = call i32 @fget(ptr %fr, i32 3)
  %imap0 = call i32 @fget(ptr %fr, i32 4)
  %imap = icmp ne i32 %imap0, 0
  %iend = sub i32 %isz, 1
  br label %il_loop
il_loop:
  %ii = phi i32 [ %ii0, %inl_run ], [ %ii1, %il_next ]
  %idp = phi i32 [ 0, %inl_run ], [ %idp1, %il_next ]
  %ilc = icmp sle i32 %ii, %iend
  br i1 %ilc, label %il_body, label %il_after
il_body:
  %iaddr = add i32 %iva, %ii
  %ich = call i8 @at8(ptr %s, i32 %iaddr)
  switch i8 %ich, label %il_plain [
    i8 91, label %il_open
    i8 123, label %il_open
    i8 93, label %il_close
    i8 125, label %il_close
    i8 44, label %il_comma
  ]
il_plain:
  br label %il_next
il_open:
  %idpo = add i32 %idp, 1
  br label %il_next
il_close:
  %idpos = icmp sgt i32 %idp, 0
  %idpm = sub i32 %idp, 1
  %idpc = select i1 %idpos, i32 %idpm, i32 %idp
  br label %il_next
il_comma:
  %iatz = icmp eq i32 %idp, 0
  br i1 %iatz, label %il_token, label %il_plainc
il_plainc:
  br label %il_next
il_next:
  %idp1 = phi i32 [ %idp, %il_plain ], [ %idpo, %il_open ], [ %idpc, %il_close ], [ %idp, %il_plainc ]
  %ii1 = add i32 %ii, 1
  br label %il_loop
il_token:
  %itn = add i32 %ii, 1
  call void @fset(ptr %fr, i32 3, i32 %itn)
  %ita = add i32 %iva, %ii0
  %itb = add i32 %iva, %ii
  store i32 %ita, ptr %t_a, align 4
  store i32 %itb, ptr %t_b, align 4
  br label %il_tokgo
il_after:
  %ilast = icmp slt i32 %ii0, %iend
  br i1 %ilast, label %il_final, label %il_pop
il_final:
  %ifn = add i32 %iend, 1
  call void @fset(ptr %fr, i32 3, i32 %ifn)
  %ifa = add i32 %iva, %ii0
  %ifb = add i32 %iva, %iend
  store i32 %ifa, ptr %t_a, align 4
  store i32 %ifb, ptr %t_b, align 4
  br label %il_tokgo
il_pop:
  %ipsp = call i32 @yget(ptr %Y, i32 10)
  %ipsp1 = sub i32 %ipsp, 1
  call void @yset(ptr %Y, i32 10, i32 %ipsp1)
  br label %resume

; the token [t_a, t_b): a mapping's pair or a sequence's element
il_tokgo:
  %ta = load i32, ptr %t_a, align 4
  %tb = load i32, ptr %t_b, align 4
  br i1 %imap, label %il_pair, label %il_seqtok
il_seqtok:
  store i32 %ta, ptr %k_a, align 4
  store i32 %tb, ptr %k_b, align 4
  br label %tokval
il_pair:
  %pr = call { i32, i32 } @y_trimi(ptr %Y, i32 %ta, i32 %tb)
  %pa2 = extractvalue { i32, i32 } %pr, 0
  %pb2 = extractvalue { i32, i32 } %pr, 1
  %pcol = call i32 @y_indexof(ptr %Y, i32 %pa2, i32 %pb2, i8 58)
  %pfound = icmp ne i32 %pcol, -1
  br i1 %pfound, label %il_pair2, label %resume
il_pair2:
  %kr = call { i32, i32 } @y_trimi(ptr %Y, i32 %pa2, i32 %pcol)
  %ika = extractvalue { i32, i32 } %kr, 0
  %ikb = extractvalue { i32, i32 } %kr, 1
  %iklen = sub i32 %ikb, %ika
  %ikst = call i32 @y_key_row(ptr %Y, i32 1, i32 %iklen)
  %ikbad = icmp ne i32 %ikst, 0
  br i1 %ikbad, label %fail, label %il_pair3
il_pair3:
  %pv = add i32 %pcol, 1
  store i32 %pv, ptr %k_a, align 4
  store i32 %pb2, ptr %k_b, align 4
  br label %tokval

; parseInlineToken: the text [k_a, k_b), trimmed
tokval:
  %xa = load i32, ptr %k_a, align 4
  %xb = load i32, ptr %k_b, align 4
  %xr = call { i32, i32 } @y_trimi(ptr %Y, i32 %xa, i32 %xb)
  %xa3 = extractvalue { i32, i32 } %xr, 0
  %xb3 = extractvalue { i32, i32 } %xr, 1
  %xempty = icmp sle i32 %xb3, %xa3
  br i1 %xempty, label %tnull, label %tnon
tnull:
  %tnst = call i32 @y_scalar_row(ptr %Y, i32 1, i32 1, i32 0)
  %tnbad = icmp ne i32 %tnst, 0
  br i1 %tnbad, label %fail, label %resume
tnon:
  %xc0 = call i8 @at8(ptr %s, i32 %xa3)
  %xisb = icmp eq i8 %xc0, 91
  %xisc = icmp eq i8 %xc0, 123
  %xisfl = or i1 %xisb, %xisc
  br i1 %xisfl, label %tflow, label %tscal
tflow:
  %xsz = sub i32 %xb3, %xa3
  store i32 %xa3, ptr %pi_a, align 4
  store i32 %xsz, ptr %pi_size, align 4
  %xmap = zext i1 %xisc to i32
  store i32 %xmap, ptr %pi_map, align 4
  store i32 1, ptr %pi_line, align 4
  br label %push_inl
tscal:
  %xkind = call i32 @y_classify(ptr %Y, i32 %xa3, i32 %xb3)
  %xst = call i32 @y_scalar_row(ptr %Y, i32 1, i32 1, i32 %xkind)
  %xbad = icmp ne i32 %xst, 0
  br i1 %xbad, label %fail, label %resume

finish:
  %total = call i32 @yget(ptr %Y, i32 3)
  %fres = call i32 @fin(ptr %flat, i32 %total, i32 %n, i32 0, i32 0, i32 1)
  ret i32 %fres
backward:
  call void @yset(ptr %Y, i32 13, i32 4)
  br label %fail
fail:
  %ff = call i32 @yget(ptr %Y, i32 13)
  %frows = call i32 @yget(ptr %Y, i32 3)
  %fres2 = call i32 @fin(ptr %flat, i32 %frows, i32 %n, i32 %ff, i32 0, i32 0)
  ret i32 %fres2
}
