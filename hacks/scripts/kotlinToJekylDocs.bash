#!/usr/bin/env bash


# this script converts the kotlin docs to jekyll static site format
# the script begins by ascending up to 3 levels from CWD to find gradlew

[[ -f gradlew ]] && GRADLEW=./gradlew
[[ -f ../gradlew ]] && GRADLEW=../gradlew
[[ -f ../../gradlew ]] && GRADLEW=../../gradlew
[[ -f ../../../gradlew ]] && GRADLEW=../../../gradlew

if [[ -z $GRADLEW ]]; then
    echo "Could not find gradlew"
    exit 1
fi

# store the new CWD in GRADLEPROJROOT  var for later when we git clone the gh-pages branch in tmp
export GRADLEPROJROOT=$(pwd)



done

# check if the gradle project is kotlin or groovy dialect

if [[ -f build.gradle.kts ]]; then
    DIALECT=kotlin
elif [[ -f build.gradle ]]; then
    DIALECT=groovy
else
    echo "Could not find build.gradle or build.gradle.kts"
    exit 1
fi

#check if the project has latest dokka plugin configured or add/update/modify the project to use the
# latest dokka plugin using gnu textutils

if [[ $DIALECT == "kotlin" ]]; then
    if grep -q "org.jetbrains.dokka" build.gradle.kts; then
        echo "Dokka plugin already configured"
    else
        echo "Dokka plugin not configured"
        echo "Adding dokka plugin to build.gradle.kts"
        sed -i 's/plugins {/plugins {\n    id("org.jetbrains.dokka") version \"1.4.32\"/' build.gradle.kts
    fi
else
    if grep -q "org.jetbrains.dokka" build.gradle; then
        echo "Dokka plugin already configured"
    else
        echo "Dokka plugin not configured"
        echo "Adding dokka plugin to build.gradle"
        sed -i 's/plugins {/plugins {\n    id("org.jetbrains.dokka") version \"1.4.32\"/' build.gradle
    fi
fi

# run the gradle task to generate the docs

$GRADLEW dokkaHtml

# check if the docs are generated

if [[ -d build/dokka/html ]]; then
    echo "Docs generated successfully"
else
    echo "Docs not generated"
    exit 1
fi

# copy the docs to the jekyll docs folder

cp -r build/dokka/html docs

# remove the docs folder from the root of the project

rm -rf build/dokka/html

# identify the jekyll requirements are met or create a run script in /tmp/ with a heredoc to perform user account
# installs of jekyll and supporting gems

if [[ -f /usr/bin/gem ]]; then
    echo "Jekyll requirements met"
else
    echo "Jekyll requirements not met"
    echo "Creating run script"
# identify whether debian, rpm, gentoo, or arch based package manager is installed
    if [[ -f /usr/bin/apt-get ]]; then
        echo "Debian based package manager detected"
        echo "Installing jekyll requirements"
        cat > /tmp/run.sh << EOF
#!/usr/bin/env bash
sudo apt-get install ruby-full build-essential zlib1g-dev
sudo gem install jekyll bundler
EOF
    elif [[ -f /usr/bin/dnf ]]; then
        echo "RPM based package manager detected"
        echo "Installing jekyll requirements"
        cat > /tmp/run.sh << EOF
#!/usr/bin/env bash
sudo dnf install ruby ruby-devel gcc gcc-c++ zlib-devel
sudo gem install jekyll bundler
EOF
    elif [[ -f /usr/bin/emerge ]]; then
        echo "Gentoo based package manager detected"
        echo "Installing jekyll requirements"
        cat > /tmp/run.sh << EOF
#!/usr/bin/env bash
sudo emerge dev-lang/ruby dev-ruby/rubygems dev-ruby/bundler dev-ruby/rdoc
sudo gem install jekyll bundler
EOF
    elif [[ -f /usr/bin/pacman ]]; then
        echo "Arch based package manager detected"
        echo "Installing jekyll requirements"
        cat > /tmp/run.sh << EOF
#!/usr/bin/env bash
sudo pacman -S ruby ruby-bundler
sudo gem install jekyll bundler
EOF
    else
        echo "Could not identify package manager"
        exit 1
    fi
    chmod +x /tmp/run.sh
    /tmp/run.sh
fi

# check if the jekyll docs folder exists

if [[ -d docs ]]; then
    echo "Jekyll docs folder exists"
else
    echo "Jekyll docs folder does not exist"
    exit 1
fi

# ensure the jekyll docs folder has the required files and folders for jekyll to build the static site

if [[ -f docs/_config.yml ]]; then
    echo "Jekyll config file exists"
else
    echo "Jekyll config file does not exist"
    echo "Creating jekyll config file"
    cat > docs/_config.yml << EOF
title: My Docs
description: My Docs
baseurl: /my-docs
url: https://my-docs.com
EOF
fi

if [[ -f docs/index.md ]]; then
    echo "Jekyll index file exists"
else
    echo "Jekyll index file does not exist"
    echo "Creating jekyll index file"
    cat > docs/index.md << EOF
---
layout: default
title: My Docs
nav_order: 1
---
# My Docs
EOF
fi

if [[ -f docs/_layouts/default.html ]]; then
    echo "Jekyll default layout file exists"
else
    echo "Jekyll default layout file does not exist"
    echo "Creating jekyll default layout file"
    cat > docs/_layouts/default.html << EOF
<!DOCTYPE html>
<html lang="en">
\t<head>
\t\t<meta charset="utf-8">
\t\t<meta http-equiv="X-UA-Compatible" content="IE=edge">
\t\t<meta name="viewport" content="width=device-width, initial-scale=1">
\t\t<title>{{ page.title }}</title>
\t\t<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/4.7.0/css/font-awesome.min.css">
\t\t<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/9.18.1/styles/default.min.css">
\t\t<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/jekyll-theme-cayman/0.1.1/css/jekyll-theme-cayman.min.css">
\t\t<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/jekyll-theme-cayman/0.1.1/css/jekyll-theme-cayman.css">
\t\t<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/jekyll-theme-cayman/0.1.1/css/jekyll-theme-cayman.css.map">

\t\t<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/9.18.1/highlight.min.js"></script>
\t\t<script>hljs.initHighlightingOnLoad();</script>
\t</head>
\t<body>
\t\t{{ content }}
\t</body>
</html>
EOF
fi

# build the jekyll site

cd docs
bundle install
bundle exec jekyll build


# check if the jekyll site is built

if [[ -d _site ]]; then
    echo "Jekyll site built successfully"
else
    echo "Jekyll site not built"
    exit 1
fi

# pushd to /tmp and git clone the project to tmp folder and checkout the gh-pages branch

pushd /tmp
git clone $GRADLEPROJROOT jekconversion && pushd jekconversion
git checkout gh-pages

# copy the jekyll site to the root of the tmp project

cp -r /tmp/jekconversion/docs/_site/* /tmp/jekconversion

# remove the docs folder and the jekyll config file

rm -rf /tmp/jekconversion/docs
rm -rf /tmp/jekconversion/_config.yml

# commit the changes and push to the gh-pages branch

git add .
git commit -m "Converted to jekyll"
git push origin gh-pages

# popd back to the original directory

popd
popd

# remove the jekyll docs folder

rm -rf docs

# remove the jekyll requirements script

rm -rf /tmp/run.sh






