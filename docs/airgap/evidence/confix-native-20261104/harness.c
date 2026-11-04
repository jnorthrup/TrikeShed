
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <assert.h>
#include <sanitizer/asan_interface.h>
#undef offsetof
#include "bridge-snapshot.c"
static unsigned long cases;
static void poison(Scan *s) {
    uint64_t n=s->source.size,r=s->flat.room,w=9*(n+1)>3*r?9*(n+1):3*r;
    uint64_t sizes[]={n,8*r,r,4*r,4*(r+1),4*r,4*w};
    for(int i=0;i<7;i++) {
        uint8_t *p=(uint8_t*)(s->guards[2*i]+1);
        __asan_poison_memory_region(s->guards[2*i],8);
        __asan_poison_memory_region(p+sizes[i],aligned(sizes[i])-sizes[i]+8);
    }
}
static void guards(Scan *s) {
    for(int i=0;i<14;i++) __asan_unpoison_memory_region(s->guards[i],8);
    assert(confix_guards(s)==1);
}
static int scan(int format,const unsigned char *data,int n,int room,int expected,int verbose) {
    Scan *s=confix_open(n,room); assert(s);
    for(int i=0;i<n;i+=4) { uint32_t lane=0; for(int k=0;k<4&&i+k<n;k++) lane|=(uint32_t)data[i+k]<<(8*k); assert(confix_write(s,i,(int32_t)lane)==0); }
    assert(confix_write(s,-1,0)==-1); assert(confix_write(s,n,0)==-1);
    if(verbose) { fprintf(stderr,"CASE %lu f=%d n=%d r=%d hex=",cases,format,n,room); for(int i=0;i<n;i++)fprintf(stderr,"%02x",data[i]);fputc('\n',stderr);fflush(stderr); }
    poison(s);
    int fault=confix_run(s,format); assert(fault>=0&&fault<=5); assert(confix_header(s,1)==fault);
    if(expected>=0&&fault!=expected) { fprintf(stderr,"EXPECTED %d GOT %d\n",expected,fault);abort(); }
    int total=confix_header(s,0); assert(total>=0&&total<=room);
    assert(confix_cell(s,-1,0)==-1); assert(confix_cell(s,total,0)==-1); assert(confix_child(s,0,-1)==-1);
    if(fault) { assert(confix_cell(s,0,0)==-1); assert(confix_child(s,0,0)==-1); }
    else for(int row=0;row<total;row++) {
        int a=confix_cell(s,row,0),b=confix_cell(s,row,1),tag=confix_cell(s,row,2),depth=confix_cell(s,row,3),count=confix_cell(s,row,4);
        assert(b<a||(a>=0&&b<n)); assert(tag>=0&&tag<=7);assert(depth>=0&&depth<total);assert(count>=0&&count<=total);
        for(int i=0;i<count;i++){int child=confix_child(s,row,i);assert(child>=0&&child<total);}
        assert(confix_child(s,row,count)==-1);
    }
    guards(s);confix_close(s);assert(confix_live_bytes()==0);cases++;return fault;
}
static void geometry(int f,const unsigned char *data,int n,int rows,const int *a,const int *b,const int *tag,const int *depth,const int *starts,const int *kids) {
    Scan *s=confix_open(n,rows);assert(s);for(int i=0;i<n;i++)assert(confix_write(s,i,data[i])==0);poison(s);assert(confix_run(s,f)==0);assert(confix_header(s,0)==rows);
    for(int i=0;i<rows;i++){assert(confix_cell(s,i,0)==a[i]);assert(confix_cell(s,i,1)==b[i]);assert(confix_cell(s,i,2)==tag[i]);assert(confix_cell(s,i,3)==depth[i]);assert(confix_cell(s,i,4)==starts[i+1]-starts[i]);for(int j=starts[i];j<starts[i+1];j++)assert(confix_child(s,i,j-starts[i])==kids[j]);}
    assert(confix_header(s,3)==-1);assert(confix_cell(s,0,5)==-1);guards(s);confix_close(s);assert(confix_live_bytes()==0);cases++;
}
static void fixture(int f,const char *s,int room,int expect){scan(f,(const unsigned char*)s,(int)strlen(s),room,expect,1);}
static uint32_t rng=0x81f72513u;
static uint32_t next(void){rng^=rng<<13;rng^=rng>>17;rng^=rng<<5;return rng;}
int main(int argc,char **argv) {
    assert(confix_abi()==1);assert(confix_live_bytes()==0);
    assert(!confix_open(-1,1));assert(!confix_open(65537,1));assert(!confix_open(1,0));assert(!confix_open(1,16385));
    assert(confix_run(NULL,0)==-1);assert(confix_header(NULL,0)==-1);assert(confix_cell(NULL,0,0)==-1);assert(confix_child(NULL,0,0)==-1);confix_close(NULL);
    if(argc<2||!strcmp(argv[1],"fixtures")) {
        fixture(0,"",1,0);fixture(1,"",1,0);fixture(2,"",1,0);
        {const unsigned char d[]="[1,true,null,\"x\"]";int a[]={0,1,3,8,13},b[]={16,1,6,11,15},t[]={6,3,1,0,4},z[]={0,1,1,1,1},st[]={0,4,4,4,4,4},k[]={1,2,3,4};geometry(0,d,sizeof(d)-1,5,a,b,t,z,st,k);}
        {const unsigned char d[]={0xa2,0x61,'a',0x82,1,2,0x61,'b',0xf5};int a[]={0,1,3,4,5,6,8},b[]={8,2,5,4,5,7,8},t[]={5,4,6,2,2,4,1},z[]={0,1,1,2,2,1,1},st[]={0,4,4,6,6,6,6,6},k[]={1,2,5,6,3,4};geometry(1,d,sizeof(d),7,a,b,t,z,st,k);}
        {const unsigned char d[]="a: 1\n";int a[]={0,0,0},b[]={4,0,3},t[]={5,4,3},z[]={0,1,1},st[]={0,2,2,2},k[]={1,2};geometry(2,d,sizeof(d)-1,3,a,b,t,z,st,k);}

        fixture(0,"[1,true,null,\"x\"]",5,0);fixture(0,"[1,true,null,\"x\"]",4,5);
        fixture(0,"{\"text\":\"é汉😀\\\"x\",\"n\":-1.25e3}",32,0);
        fixture(0,"]-+-[\"unfinished\\",32,0);
        fixture(2,"a: 1\nb: [true, false]\n",64,0);
        fixture(2,"\r\n# hi\r\na: 1\r\nb: {x: y}\r\n",64,0);
        fixture(2,"- # comment: value",64,4);
        fixture(2,"v: 1e++5",16,0);
        unsigned char valid[]={0xa2,0x61,'a',0x82,1,2,0x61,'b',0xf5};scan(1,valid,sizeof valid,7,0,1);scan(1,valid,sizeof valid,6,5,1);
        unsigned char trunc[]={0x1a,0};scan(1,trunc,sizeof trunc,8,1,1);
        unsigned char ai[]={0x1c};scan(1,ai,sizeof ai,8,2,1);
        unsigned char bounds[]={0xfb};scan(1,bounds,sizeof bounds,8,3,1);
        unsigned char back[]={0x5b,0,0,0,0,0xff,0xff,0xff,0xf7};scan(1,back,sizeof back,8,4,1);
        unsigned char all[256];for(int i=0;i<256;i++)all[i]=i;for(int f=0;f<3;f++)scan(f,all,256,512,-1,1);
    } else if(!strcmp(argv[1],"fuzz")) {
        unsigned count=argc>2?strtoul(argv[2],NULL,10):30000; rng=argc>3?strtoul(argv[3],NULL,10):rng;
        static const unsigned char text[]=" \t\r\n#-:,+.eE0123456789[]{}\"'\\truefalsnul|>&*!?é";
        unsigned char data[192];
        for(unsigned i=0;i<count;i++) { int f=i%3,n=next()%sizeof data,r=1+next()%256;for(int j=0;j<n;j++) data[j]=(f==1||i%7==0)?next()&255:text[next()%(sizeof(text)-1)];scan(f,data,n,r,-1,1); }
     } else if(!strcmp(argv[1],"asan-control")) {
        Scan *s=confix_open(1,4);confix_write(s,0,'1');poison(s);s->source.size=2;confix_run(s,0);abort();
    } else if(!strcmp(argv[1],"structured")) {
        const char *seeds[]={"- key: value\n  nested: [a, {b: c}]\n", "a:\n  - x\n  - y\n", "\n\nk: [1,2]\n", "- # comment: x\n", "a: {'x:y': [a, b], c: 'a,b'}\n", "x: \"é汉😀\"\r\ny: null\r\n", "- - - # comment: x\n", "\n\n- a: b\n  c:\n    - d\n"};
        const char *edits="\n\r\t :,-#[]{}\"'\\";
        unsigned count=argc>2?strtoul(argv[2],NULL,10):30000;unsigned char data[512];
        for(unsigned i=0;i<count;i++) {const char *seed=seeds[next()%(sizeof(seeds)/sizeof(seeds[0]))];int n=0,prefix=next()%24;for(int k=0;k<prefix;k++)data[n++]='\n';int reps=1+next()%4;for(int k=0;k<reps;k++){int z=strlen(seed);memcpy(data+n,seed,z);n+=z;}int changes=next()%6;for(int k=0;k<changes;k++)data[next()%n]=edits[next()%strlen(edits)];scan(2,data,n,1+next()%256,-1,1);}
    } else if(!strcmp(argv[1],"inspect")) {
        const char *txt=argc>2?argv[2]:"v: 1e++5";int n=strlen(txt);Scan *s=confix_open(n,256);for(int i=0;i<n;i++)confix_write(s,i,(uint8_t)txt[i]);poison(s);int f=confix_run(s,2);printf("fault=%d total=%d\n",f,s->flat.total);if(!f)for(int i=0;i<s->flat.total;i++)printf("row=%d open=%d close=%d tag=%d depth=%d kids=%d\n",i,confix_cell(s,i,0),confix_cell(s,i,1),confix_cell(s,i,2),confix_cell(s,i,3),confix_cell(s,i,4));guards(s);confix_close(s);
    } else if(!strcmp(argv[1],"max")) {
        unsigned char *p=malloc(65536);assert(p);memset(p,'[',65536);scan(0,p,65536,16384,5,1);
        memset(p,'\n',65536);scan(2,p,65536,16384,0,0);memset(p,0x81,65536);scan(1,p,65536,16384,5,0);free(p);
    }
    printf("PASS %lu cases live_bytes=%llu\n",cases,(unsigned long long)confix_live_bytes());return 0;
}
