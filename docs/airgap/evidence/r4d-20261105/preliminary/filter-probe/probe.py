import os,json,sys
results=[]
for name in ['b','a']:
 fd=os.open(sys.argv[1]+'/'+name,os.O_WRONLY)
 try:
  os.fsync(fd);results.append({'file':name,'result':0})
 except OSError as e:results.append({'file':name,'errno':e.errno})
 finally:os.close(fd)
print(json.dumps(results))
