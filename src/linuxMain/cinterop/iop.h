

#pragma once

#include <liburing.h>

struct cqe_parms{
    struct io_uring *ring;
    unsigned int head;
    struct io_uring_cqe *cqe;
 } ;
static inline int io_uring_do_for_each_cqe(struct cqe_parms  cqep ,int (*vfunc)(struct   cqe_parms))  {

    struct io_uring *ring = cqep.ring;
    struct io_uring_cqe *cqe = cqep.cqe;
    unsigned int head = cqep.head;
    int x=0;
    io_uring_for_each_cqe(ring, head, cqe) { \
        struct  cqe_parms a={};\
        a.ring=ring;\
        a.head=head;\
        a.cqe=cqe;\
        x = vfunc(a);
        if(x) break;
    }
    return x;
};


static inline int io_uring_do_for_each_cqe2(struct io_uring*ring,void*data,int(*vfunc2)(struct io_uring*ring,unsigned head,struct io_uring_cqe*cqe,void*data)){
    unsigned int  head=0;
    struct  io_uring_cqe*cqe=NULL;
    int x=0;
    io_uring_for_each_cqe(ring, head, cqe) {
        x = vfunc2(ring, head, cqe, data );
        if(x) break;
    }
    return x;
};

