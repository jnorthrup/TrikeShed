
#how would we download the ffmepg aur, and the gentoo ffmpeg ebuild, and then build a round-trip transpiler from that example?
#1. download the aur ffmpeg
#2. download the gentoo ffmpeg ebuild
#3. build a transpiler from the two

#1. download the aur ffmpeg
git clone https://aur.archlinux.org/ffmpeg.git

#2. download the gentoo ffmpeg ebuild
git clone https://gitweb.gentoo.org/repo/gentoo.git ffmpeg  #this is the ebuild, not the source

#the bnf for the ebuild is: https://devmanual.gentoo.org/ebuild-writing/variables/index.html

#the bnf for aur files is here: https://wiki.archlinux.org/index.php/PKGBUILD