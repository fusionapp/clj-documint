(set-env!
 :resource-paths #{"src"}
 :dependencies '[[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [danielsz/boot-environ "0.0.5"]
                 [org.danielsz/system "0.2.0"]
                 [org.xhtmlrenderer/flying-saucer-pdf-itext5 "9.0.9"]
                 [ring/ring-defaults "0.2.0"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [ring/ring-core "1.4.0"]
                 [danlentz/clj-uuid "0.1.6"]
                 [aleph "0.4.1"]
                 [liberator "0.14.1"]
                 [bidi "2.0.7"]
                 [compojure "1.5.0"]
                 [environ "1.0.2"]
                 [manifold "0.1.4"]
                 [org.apache.pdfbox/pdfbox "2.0.0"]
                 [org.bouncycastle/bcpkix-jdk15on "1.54"]
                 [org.bouncycastle/bcprov-jdk15on "1.54"]
                 [prismatic/schema "1.1.0"]
                 [com.twelvemonkeys.imageio/imageio-tiff "3.2.1"]
                 [com.twelvemonkeys.imageio/imageio-metadata "3.2.1"]
                 [com.levigo.jbig2/levigo-jbig2-imageio "1.6.5"]
                 [com.climate/claypoole "1.1.2"]
                 ])


(require
 '[reloaded.repl :as repl :refer [start stop go reset]]
 '[documint.systems :refer [dev-system]]
 '[danielsz.boot-environ :refer [environ]]
 '[system.boot :refer [system run]])


(deftask dev
  "Run a restartable system in the Repl"
  []
  (comp
   (watch :verbose true)
   (system :sys #'dev-system :auto-start true :hot-reload true)
   (repl :server true)))


(deftask dev-run
  "Run a dev system from the command line"
  []
  (comp
   (run :main-namespace "documint.core" :arguments [#'dev-system])
   (wait)))


(deftask build
  "Builds an uberjar of this project that can be run with java -jar"
  []
  (comp
   (aot :namespace '#{documint.core})
   (pom :project 'myproject
        :version "0.1.0-SNAPSHOT")
   (uber)
   (jar :main 'documint.core)))
