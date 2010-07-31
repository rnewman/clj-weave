# clj-weave

`clj-weave` is a native Clojure client for the [Weave 1.0 Sync API](https://wiki.mozilla.org/Labs/Weave/Sync/1.0/API).

For now it only implements the `GET`-based consumption part of the API. (Go easy: it was written in four hours!)


## Quick Start

`clj-weave` uses [Leiningen](http://github.com/technomancy/leiningen) to manage dependencies. To get the source and dependencies such that you can run it:

    $ git clone http://github.com/rnewman/clj-weave
    $ cd clj-weave
    $ lein deps


Alternatively, add it to your Leiningen project file, and allow `lein` to add it to your dependencies:

    [weave/weave "0.0.1"]


## How it works

A layer of functions take explicit username, URL, and password inputs:

* `weave-collections-timestamps`
* `weave-collections-counts`
* `weave-collections`
* `weave-usage`
* `weave-collection`
* `weave-object`

A layer on top of this expects these arguments to be implicit, allowing you to concern yourself with only the important inputs:

* `collections-timestamps`
* `collections-counts`
* `collections`
* `usage`
* `collection`
* `object`

A macro `with-weave` provides these inputs. (You can also alter the var roots directly: take a look at the code if you want to do this.)

Finally, the function `decrypt-payload` exists to do exactly that (remember, a Sync server stores your information encrypted with your private key).

`decrypt-payload` takes an optional map of decryption input — your passphrase, your private key (as a byte array), or a map from bulk URLs to keys. It returns an augmented map as its second return value, which allows you to avoid redundant fetches of keys in subsequent requests: this map will include your private key and the fetched bulk URLs.


## Usage

Here's an example REPL session, showing me fetching some of my current tabs, and listing collections.

    user=> (require ['weave.core :as 'weave])                                                                                                  
    nil

    user=> (weave/with-weave ["holygoat" "PaSsWoRd"]
      (weave/collections))                     
    (:crypto :history :forms :clients :prefs :meta :passwords :keys :bookmarks :tabs)

    user=> (weave/with-weave ["holygoat" "PaSsWoRd"]
      (weave/collection :tabs {:limit 5}))     
    ["yR)o!!HbXX"]

    user=> (weave/with-weave ["holygoat" "PaSsWoRd"]
      ;; Fetch the object that represents my open tabs.
      (let [p (:payload (weave/object :tabs "yR)o!!HbXX"))]

        ;; Decrypt the payload. We discard auth here, but you can reuse it.
        (let [[o auth] (weave/decrypt-payload p {:passphrase "Pass Phrase Goes Here"})]

          ;; `o` is a JSON object. Here we're pulling out the titles of each tab.
          (take 3 (map :title (:tabs o))))))
    ("Labs/Weave/Sync/1.0/API - MozillaWiki" "Open Source Profilers in Java - JMP" "Open Web Fund")

