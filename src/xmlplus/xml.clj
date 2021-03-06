(ns xmlplus.xml
  (:require [clojure.zip :as zip]
            [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.zip :as zf]
            [clojure.data.zip.xml :as zfx]))


(defn parse-file
  "Parse a given xml file and return location of the root"
  [file]
  (zip/xml-zip
    (with-open [ins (io/input-stream file)]
      (xml/parse ins))))

(defn parse-str
  "Parse a given xml string and return location of the root"
  [^String s]
  (zip/xml-zip
    (xml/parse (java.io.ByteArrayInputStream. (.getBytes s)))))

(defn root-loc
  "Get the root location of the given loc."
  [loc]
  (-> loc zip/root zip/xml-zip))

(defn tag-not=
  "Returns a query predicate that matches a node when its is a tag
  not named tagname."
  [tagname]
  (fn [loc]
    (filter #(and (zip/branch? %) (not= tagname (:tag (zip/node %))))
            (if (zf/auto? loc)
              (zf/children-auto loc)
              (list (zf/auto true loc))))))

(defn texts
  "This function get texts from all descendant nodes at given location
  and return them as a lazy seq. Note this is different from
  clojure.data.zip.xml/text which returns a single concatenated string."
  [loc]
  (zfx/xml-> loc zf/descendants zip/node string?))

(defn texts=
  "Return a function to test whether given text is equal to the concatenation
  of all the texts under the given node.
  This behaviour is desriable instead of comparing the given string to each of
  string individually under the given node, which always returnt the root node."
  [s]
  (fn [loc] (= (apply str (texts loc)) s)))

(defn texts=*
  "Similar to texts= but returns a function takes a regex pattern.
  Note that it is most usually necessary to have ^ and $ around the
  regex pattern. Otherwise the match will always be the root node."
  [p]
  (fn [loc] ((complement nil?) (re-find p (apply str (texts loc))))))

(defn text
  "This function get only text directly belong to the node at given location"
  [loc]
  (apply str (filter string? (-> loc first :content))))

(defn text=
  "Predicate function to test if text directly belong to the given node is
   equal to the given text."
  [s]
  (fn [loc] (= (text loc) s)))

(defn text=*
  "Similar to text= but returns a function takes a regex pattern"
  [p]
  (fn [loc] ((complement nil?) (re-find p (text loc)))))

(defn attr?
  "Returns function for checking whether the node at given loction has the given attribute"
  ([att]
   (fn [loc] (boolean (some #{att} (-> loc zip/node :attrs keys)))))
  ([loc att]
   ((attr? att) loc)))

(defn x->
  "Similar to clojure.data.zip/xml-> with the ability to take integer
  as index filter. Note this function always returns a lazy seq even
  when index filter is used."
  [loc & args]
  (loop [res loc preds args]
    (let [pred (first preds)
          pred (if (string? pred) (text= pred) pred)]
      (cond
        (nil? pred) (if (seq? res) res (lazy-seq (conj () res)))
        (number? pred)
          (recur (->> res (drop pred) (take 1)) (rest preds))
        (vector? res) ; single res of location type (vector)
          (recur (zfx/xml-> res pred) (rest preds))
        ; lazy-seq of multiple res
        :else (recur (mapcat #(zfx/xml-> % pred) res) (rest preds))))))

(defn x1->
  "Similar to x-> but guarantee to return a single location."
  [loc & args]
  (first (apply x-> loc args)))

; Similar to x-> and x1-> but starts the tag match from root node
(def x->* #(apply x-> (zf/auto false %) %&))
(def x1->* #(apply x1-> (zf/auto false %) %&))


(defn- empty-node?
  "An empty node is one that has no subtree or string content,
  i.e. content is nil."
  [loc]
  (if (string? (loc 0))
    false
    (let [content (get-in loc [0 :content])]
      (nil? content))))

(defn filled?
  "Returns a function to check whether the node at given location is filled.
  It can be custmoized to return a function just like xmlplus.xml/empty-node?
  It can also take extra attributes to exclude valid empty nodes, i.e. nodes
  with attributes indicating it should be empty."
  [& attrs]
  (fn [loc]
    (let [att-filter (for [attr attrs] (if (vector? attr) (apply zfx/attr= attr) (attr? attr)))
          all-filter (cons (complement empty-node?) att-filter)]
      (boolean (some #{true} (for [f all-filter] (f loc)))))))

(defn not-filled?
  "Just a wrapper of filled? to give the opposite answer"
  [& attrs]
  (complement (apply filled? attrs)))

(defn text-node?
  "A node is a text node if some of its contents is string.
  A more strict definition of text node requires all of its
  contents are strings. This behaviour can be turned on by
  setting the :pure keyword to true."
  [loc & {:as opts}]
  (let [pure (:pure opts false)
        content (get-in loc [0 :content])]
    (boolean ((if pure every? some) string? content))))

(defn path
  "Get the complete path to root node from given loc.
  The path can be used as arguments to x-> and get the loc back.
  By default, the path is composed by tags from root to the node at
  given location, but exclude the root node tag. This is because
  clojure.data.zip.xml/xml-> function does not require the root tag
  as its arguments.
  To change the default behaviour and include the root-tag, pass
  :include-root true to the function."
  [loc & {:as opts}]
  (flatten
    (for [ancestor (-> (zf/ancestors loc) ((if (:include-root opts) identity butlast)) reverse)]
      (do
        (let [tag (-> ancestor first :tag)
              left (-> ancestor second :l)
              cnt (count (filter #(= tag (:tag %)) left))]
          (if (> cnt 0) [tag cnt] tag))))))

; Similar to path but always include root node tag
(def path* #(path % :include-root true))

(defn edit-tag
  "Edit the tag of the node at given location."
  [loc tag]
  (zip/edit loc #(assoc % :tag tag)))

(defn edit-text
  "Edit text of the node at given location"
  [loc text & {:as opts}]
  (let [append? (:append opts false)
        ct (if append? (conj (-> loc first :content) text) (vector text))]
    (zip/edit loc #(assoc % :content ct))))

(defn edit-attrs
  "Edit the attrs of the node at given location.
  :dissoc [] to remove existing attrs."
  [loc attrs & {:as opts}]
  (let [ex-attrs (-> loc zip/node :attrs)
        loc (zip/edit loc #(assoc % :attrs (merge ex-attrs attrs)))
        attrs (-> loc zip/node :attrs)
        dis (:dissoc opts nil)]
    (if dis
      (zip/edit loc #(assoc % :attrs (apply dissoc attrs dis)))
      loc)))

(defn insert-child
  "Insert a child node at the given location based on the given position.
   Without moving the location."
  ([loc node pos]
    (let [children (zf/children loc)
          nchildren (count children)]
      (cond
        (or (= pos :last) (>= pos nchildren)) (zip/append-child loc node)
        (zero? pos) (zip/insert-child loc node)
        :else (let [child (first (drop pos children))]
                (zip/up (zip/insert-left child node))))))
  ([loc node]
    (insert-child loc node 0)))

(defn insert-left
  "Insert a node to the left on the given location"
  [loc node]
  (zip/insert-left loc node))

(defn insert-right
  "Insert a node to the right on the given location"
  [loc node]
  (zip/insert-right loc node))

(defn make-node
  "Make an node based on given tag, attrs and content"
  [tag attrs content]
  (struct xml/element tag attrs
          (if (vector? content)
            content
            (vector content))))

(defn insert-parent
  "1. Create a node with given tag and attrs.
   2. Insert this node as parent at the given location.
   3. Returns the location of the new parent node."
  [loc tag attrs]
  (let [p (make-node tag attrs (zip/node loc))]
    (zip/replace loc p)))

(defn- f-t-path
  "Make sure floc is not a anscentor of tloc. Otherwise it creates a infinite loop."
  [floc tloc]
  (let [fpath (path floc)
        tpath (path tloc)
        flen (count fpath)]
    (if (= fpath (take flen tpath)) nil [fpath tpath])))

(defn move-node
  "Move the node at given location as a child node at the second given location.
   return the location of the moved node."
  ([floc tloc pos]
    (let [node (zip/node floc)
          [fpath tpath] (f-t-path floc tloc)
          valid? (boolean fpath)]
      (if valid?
        (let [trz (-> tloc root-loc)
              trz-del (-> (apply x1-> trz fpath) zip/remove root-loc)
              tloc (insert-child (apply x1-> trz-del tpath) node pos)]
          (x1-> tloc zf/children-auto pos))
        (throw (IllegalArgumentException. ": floc cannot be ancestor of tloc.\n")) )))
  ([floc tloc]
    (move-node floc tloc 0)))

(defn copy-node
  "copy the node at given location as a child node at the second given location.
  return the location of the copied node"
  ([floc tloc pos]
    (let [node (zip/node floc)
          loc (insert-child tloc node pos)]
      (x1-> loc (:tag node) pos)))
  ([floc tloc]
    (copy-node floc tloc 0)))

; same as xpath child axis
(def z> zf/children-auto)
; same as xpath descendant axis
(def z>> (comp rest zf/descendants))
; child or self
(defn z>+
  [loc]
  (lazy-seq (cons (zf/auto false loc) (zf/children-auto loc))))
; descendant or self
(def z>>+ zf/descendants)

; parent
(def z< zip/up)
; parent or self
(defn z<+
  [loc]
  (lazy-seq (cons (zf/auto false loc) (zip/up loc))))
; ancestor
(def z<< (comp rest zf/ancestors))
; ancestor or self
(def z<<+ zf/ancestors)

(defn emit-element
  "Modified version of emit-element from clojure.xml.
  1. No EOLs are added into the tags surrounding text.
  2. Indent properly."
  [e lev]
  (let [lead (apply str (repeat (* lev 4) \space))]
    (if (string? e)
      (print e)
      (do
        (let [tag (name (:tag e))
              nottxt (not (every? string? (:content e)))]
          (print (str lead "<" tag))
          (when (:attrs e)
            (doseq [attr (:attrs e)]
              (print (str " " (name (key attr)) "=\"" (val attr) \"))))
          (if (:content e)
            (do
              (print ">")
              (if nottxt (print "\n"))
              (doseq [c (:content e)]
                (emit-element c (+ lev 1)))
              (if nottxt
                (print (str lead "</" tag ">\n"))
                (print (str "</" tag ">\n"))))
            (print "/>\n")))))))

(defn emit
  "Modified version of emit from clojure.xml."
  [node]
  (println "<?xml version=\"1.0\" encoding=\"utf-8\"?>")
  (emit-element node 0))

(defn emit*
  "Same as emit but takes a loc as argument"
  [loc]
  (emit (zip/node loc)))

(defn write-file
  "Get the root node of the given loc and write out to the given file"
  [fname loc]
  (spit fname (with-out-str (emit (zip/root loc)))))


