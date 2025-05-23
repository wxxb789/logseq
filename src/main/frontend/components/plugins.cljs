(ns frontend.components.plugins
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.components.plugins-settings :as plugins-settings]
            [frontend.components.svg :as svg]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.common.plugin :as plugin-common-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.plugin :as plugin-handler]
            [frontend.handler.plugin-config :as plugin-config-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.mixins :as mixins]
            [frontend.rum :as rum-utils]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]))

(declare open-waiting-updates-modal!)
(defonce PER-PAGE-SIZE 15)

(def *dirties-toggle-items (atom {}))

(defn- clear-dirties-states!
  []
  (reset! *dirties-toggle-items {}))

(defn render-classic-dropdown-items
  [id items]
  (for [{:keys [hr item title options icon]} items]
    (let [on-click' (:on-click options)]
      (if hr
        (shui/dropdown-menu-separator)
        (shui/dropdown-menu-item
         (assoc options
                :on-click (fn [^js e]
                            (when on-click'
                              (when-not (false? (on-click' e))
                                (shui/popup-hide! id)))))
         (or item
             [:span.flex.items-center.gap-1.w-full
              icon [:div title]]))))))

(rum/defcs installed-themes
  <
  (rum/local [] ::themes)
  (rum/local 0 ::cursor)
  (rum/local 0 ::total)
  {:did-mount (fn [state]
                (let [*themes        (::themes state)
                      *cursor        (::cursor state)
                      *total         (::total state)
                      mode           (state/sub :ui/theme)
                      all-themes     (state/sub :plugin/installed-themes)
                      themes         (->> all-themes
                                          (filter #(= (:mode %) mode))
                                          (sort-by #(:name %)))
                      no-mode-themes (->> all-themes
                                          (filter #(= (:mode %) nil))
                                          (sort-by #(:name %))
                                          (map-indexed (fn [idx opt] (assoc opt :group-first (zero? idx) :group-desc (if (zero? idx) "light & dark themes" nil)))))
                      selected       (state/sub :plugin/selected-theme)
                      themes         (map-indexed (fn [idx opt]
                                                    (let [selected? (= (:url opt) selected)]
                                                      (when selected? (reset! *cursor (+ idx 1)))
                                                      (assoc opt :mode mode :selected selected?))) (concat themes no-mode-themes))
                      themes         (cons {:name        (string/join " " ["Default" (string/capitalize mode) "Theme"])
                                            :url         nil
                                            :description (string/join " " ["Logseq default" mode "theme."])
                                            :mode        mode
                                            :selected    (nil? selected)
                                            :group-first true
                                            :group-desc  (str mode " themes")} themes)]
                  (reset! *themes themes)
                  (reset! *total (count themes))
                  state))}
  (mixins/event-mixin
   (fn [state]
     (let [*cursor    (::cursor state)
           *total     (::total state)
           ^js target (rum/dom-node state)]
       (.focus target)
       (mixins/on-key-down
        state {38                                           ;; up
               (fn [^js _e]
                 (reset! *cursor
                         (if (zero? @*cursor)
                           (dec @*total) (dec @*cursor))))
               40                                           ;; down
               (fn [^js _e]
                 (reset! *cursor
                         (if (= @*cursor (dec @*total))
                           0 (inc @*cursor))))

               13                                           ;; enter
               #(when-let [^js active (.querySelector target ".is-active")]
                  (.click active))}))))
  [state]
  (let [*cursor (::cursor state)
        *themes (::themes state)]
    [:div.cp__themes-installed
     {:tab-index -1}
     [:h1.mb-4.text-2xl.p-1 (t :themes)]
     (map-indexed
      (fn [idx opt]
        (let [current-selected? (:selected opt)
              group-first?      (:group-first opt)
              plg               (get (:plugin/installed-plugins @state/state) (keyword (:pid opt)))]
          [:div
           {:key (str idx (:name opt))}
           (when (and group-first? (not= idx 0)) [:hr.my-2])
           [:div.it.flex.px-3.py-1.5.rounded-sm.justify-between
            {:title    (:description opt)
             :class    (util/classnames
                        [{:is-selected current-selected?
                          :is-active   (= idx @*cursor)}])
             :on-click #(do (js/LSPluginCore.selectTheme (bean/->js opt))
                            (shui/dialog-close!))}
            [:div.flex.items-center.text-xs
             [:div.opacity-60 (str (or (:name plg) "Logseq") " •")]
             [:div.name.ml-1 (:name opt)]]
            (when (or group-first? current-selected?)
              [:div.flex.items-center
               (when group-first? [:small.opacity-60 (:group-desc opt)])
               (when current-selected? [:small.inline-flex.ml-1.opacity-60 (ui/icon "check")])])]]))
      @*themes)]))

(rum/defc unpacked-plugin-loader
  [unpacked-pkg-path]
  (hooks/use-effect!
   (fn []
     (let [err-handle
           (fn [^js e]
             (case (keyword (aget e "name"))
               :IllegalPluginPackageError
               (notification/show! "Illegal Logseq plugin package." :error)
               :ExistedImportedPluginPackageError
               (notification/show! (str "Existed plugin package (" (.-message e) ").") :error)
               :default)
             (plugin-handler/reset-unpacked-state))
           reg-handle #(plugin-handler/reset-unpacked-state)]
       (when unpacked-pkg-path
         (doto js/LSPluginCore
           (.once "error" err-handle)
           (.once "registered" reg-handle)
           (.register (bean/->js {:url unpacked-pkg-path}))))
       #(doto js/LSPluginCore
          (.off "error" err-handle)
          (.off "registered" reg-handle))))
   [unpacked-pkg-path])

  (when unpacked-pkg-path
    [:strong.inline-flex.px-3 "Loading ..."]))

(rum/defc category-tabs
  [t total-nums category on-action]

  [:div.secondary-tabs.categories.flex
   (ui/button
    [:span.flex.items-center
     (ui/icon "puzzle")
     (t :plugins) (when (vector? total-nums) (str " (" (first total-nums) ")"))]
    :intent "link"
    :on-click #(on-action :plugins)
    :class (if (= category :plugins) "active" ""))
   (ui/button
    [:span.flex.items-center
     (ui/icon "palette")
     (t :themes) (when (vector? total-nums) (str " (" (last total-nums) ")"))]
    :intent "link"
    :on-click #(on-action :themes)
    :class (if (= category :themes) "active" ""))])

(rum/defc local-markdown-display
  < rum/reactive
  []
  (let [[content item] (state/sub :plugin/active-readme)]
    [:div.cp__plugins-details
     {:on-click (fn [^js/MouseEvent e]
                  (when-let [target (.-target e)]
                    (when (and (= (string/lower-case (.-nodeName target)) "a")
                               (not (string/blank? (. target getAttribute "href"))))
                      (js/apis.openExternal (. target getAttribute "href"))
                      (.preventDefault e))))}
     (when-let [repo (:repository item)]
       (when-let [repo (if (string? repo) repo (:url repo))]
         [:div.p-4.rounded-md.bg-base-3
          [:strong [:a.flex.items-center {:target "_blank" :href repo}
                    [:span.mr-1 (svg/github {:width 25 :height 25})] repo]]]))
     [:div.p-1.bg-transparent.border-none.ls-block
      {:style                   {:min-height "60vw"
                                 :max-width  900}
       :dangerouslySetInnerHTML {:__html content}}]]))

(rum/defc remote-readme-display
  [{:keys [repo]} _content]
  (let [src (str (if (string/includes? js/location.host "logseq")
                   "./static/" "./") "marketplace.html?repo=" repo)]
    [:iframe.lsp-frame-readme {:src src}]))

(defn security-warning
  []
  (ui/admonition
   :warning
   [:p.text-sm
    (t :plugin/security-warning)]))

(defn format-number [num & {:keys [precision] :or {precision 2}}]
  (cond
    (< num 1000) (str num)
    (>= num 1000) (str (.toFixed (/ num 1000) precision) "k")))

(rum/defc card-ctls-of-market < rum/static
  [item stat installed? installing-or-updating?]
  [:div.ctl
   [:ul.l.flex.items-center
    ;; stars
    [:li.flex.text-sm.items-center.pr-3
     (svg/star 16) [:span.pl-1 (:stargazers_count stat)]]

    ;; downloads
    (when-let [downloads (and stat (:total_downloads stat))]
      (when (and downloads (> downloads 0))
        [:li.flex.text-sm.items-center.pr-3
         (svg/cloud-down 16) [:span.pl-1 (format-number downloads)]]))]

   [:div.r.flex.items-center

    [:a.btn
     {:class    (util/classnames [{:disabled   (or installed? installing-or-updating?)
                                   :installing installing-or-updating?}])
      :on-click #(plugin-common-handler/install-marketplace-plugin! item)}
     (if installed?
       (t :plugin/installed)
       (if installing-or-updating?
         [:span.flex.items-center [:small svg/loading]
          (t :plugin/installing)]
         (t :plugin/install)))]]])

(rum/defc card-ctls-of-installed < rum/static
  [id name url sponsors unpacked? disabled?
   installing-or-updating? has-other-pending?
   new-version item]
  [:div.ctl
   [:div.l
    [:div.de
     [:strong (ui/icon "settings")]
     [:ul.menu-list
      [:li {:on-click #(plugin-handler/open-plugin-settings! id false)} (t :plugin/open-settings)]
      (when (util/electron?)
        [:li {:on-click #(js/apis.openPath url)} (t :plugin/open-package)])
      [:li {:on-click #(plugin-handler/open-report-modal! id name)} (t :plugin/report-security)]
      [:li {:on-click
            #(-> (shui/dialog-confirm!
                  [:b (t :plugin/delete-alert name)])
                 (p/then (fn []
                           (plugin-common-handler/unregister-plugin id)

                           (when (util/electron?)
                             (plugin-config-handler/remove-plugin id)))))}
       (t :plugin/uninstall)]]]

    (when (seq sponsors)
      [:div.de.sponsors
       [:strong (ui/icon "coffee")]
       [:ul.menu-list
        (for [link sponsors]
          [:li {:key link}
           [:a {:href link :target "_blank"}
            [:span.flex.items-center link (ui/icon "external-link")]]])]])]

   [:div.r.flex.items-center
    (when (and unpacked? (not disabled?))
      [:a.btn
       {:on-click #(js-invoke js/LSPluginCore "reload" id)}
       (t :plugin/reload)])

    (when (not unpacked?)
      [:div.updates-actions
       [:a.btn
        {:class    (util/classnames [{:disabled installing-or-updating?}])
         :on-click #(when-not has-other-pending?
                      (plugin-handler/check-or-update-marketplace-plugin!
                       (assoc item :only-check (not new-version))
                       (fn [^js e] (notification/show! (.toString e) :error))))}

        (if installing-or-updating?
          (t :plugin/updating)
          (if new-version
            [:span (t :plugin/update) " 👉 " new-version]
            (t :plugin/check-update)))]])

    (ui/toggle (not disabled?)
               (fn []
                 (js-invoke js/LSPluginCore (if disabled? "enable" "disable") id)
                 (when (nil? (get @*dirties-toggle-items (keyword id)))
                   (swap! *dirties-toggle-items assoc (keyword id) (not disabled?))))
               true)]])

(defn get-open-plugin-readme-handler
  [url {:keys [webPkg] :as item} repo]
  #(plugin-handler/open-readme!
    url item (if (or repo webPkg) remote-readme-display local-markdown-display)))

(rum/defc plugin-item-card < rum/static
  [t {:keys [id name title version url description author icon iir repo sponsors webPkg] :as item}
   disabled? market? *search-key has-other-pending?
   installing-or-updating? installed? stat coming-update]

  (let [name (or title name "Untitled")
        web? (not (nil? webPkg))
        unpacked? (and (not web?) (not iir))
        new-version (state/coming-update-new-version? coming-update)]
    [:div.cp__plugins-item-card
     {:key   (str "lsp-card-" id)
      :class (util/classnames
              [{:market          market?
                :installed       installed?
                :updating        installing-or-updating?
                :has-new-version new-version}])}

     [:div.l.link-block.cursor-pointer
      {:on-click (get-open-plugin-readme-handler url item repo)}
      (if (and icon (not (string/blank? icon)))
        [:img.icon {:src (if market? (plugin-handler/pkg-asset id icon) icon)}]
        svg/folder)

      (when (and (not market?) unpacked?)
        [:span.flex.justify-center.text-xs.text-error.pt-2 (t :plugin/unpacked)])]

     [:div.r
      [:h3.head.text-xl.font-bold.pt-1.5

       [:span.l.link-block.cursor-pointer
        {:on-click (get-open-plugin-readme-handler url item repo)}
        name]
       (when (not market?) [:sup.inline-block.px-1.text-xs.opacity-50 version])]

      [:div.desc.text-xs.opacity-70
       [:p description]
     ;;[:small (js/JSON.stringify (bean/->js settings))]
       ]

    ;; Author & Identity
      [:div.flag
       [:p.text-xs.pr-2.flex.justify-between
        [:small {:on-click #(when-let [^js el (js/document.querySelector ".cp__plugins-page .search-ctls input")]
                              (reset! *search-key (str "@" author))
                              (.select el))} author]
        [:small {:on-click #(do
                              (notification/show! "Copied!" :success)
                              (util/copy-to-clipboard! id))}
         (str "ID: " id)]]]

    ;; Github repo
      [:div.flag.is-top.opacity-50
       (when repo
         [:a.flex {:target "_blank"
                   :href   (plugin-handler/gh-repo-url repo)}
          (svg/github {:width 16 :height 16})])]

      (if market?
     ;; market ctls
        (card-ctls-of-market item stat installed? installing-or-updating?)

     ;; installed ctls
        (card-ctls-of-installed
         id name url sponsors unpacked? disabled?
         installing-or-updating? has-other-pending? new-version item))]]))

(rum/defc panel-tab-search < rum/static
  [search-key *search-key *search-ref]
  [:div.search-ctls
   [:small.absolute.s1
    (ui/icon "search")]
   (when-not (string/blank? search-key)
     [:small.absolute.s2
      {:on-click #(when-let [^js target (rum/deref *search-ref)]
                    (reset! *search-key nil)
                    (.focus target))}
      (ui/icon "x")])
   (shui/input
    {:placeholder (t :plugin/search-plugin)
     :ref *search-ref
     :auto-focus true
     :on-key-down (fn [^js e]
                    (when (= 27 (.-keyCode e))
                      (util/stop e)
                      (if (string/blank? search-key)
                        (some-> (js/document.querySelector ".cp__plugins-page") (.focus))
                        (reset! *search-key nil))))
     :on-change #(let [^js target (.-target %)]
                   (reset! *search-key (some-> (.-value target) (string/triml))))
     :value (or search-key "")})])

(rum/defc panel-tab-developer
  []
  (ui/button
   (t :plugin/contribute)
   :href "https://github.com/logseq/marketplace"
   :class "contribute"
   :intent "link"
   :target "_blank"))

(rum/defc user-proxy-settings-container
  [{:keys [protocol type] :as agent-opts}]
  (let [type        (or (not-empty type) (not-empty protocol) "system")
        [opts set-opts!] (rum/use-state agent-opts)
        [testing? set-testing?!] (rum/use-state false)
        *test-input (rum/create-ref)
        disabled?   (or (= (:type opts) "system") (= (:type opts) "direct"))]
    [:div.cp__settings-network-proxy-cnt
     [:h1.mb-2.text-2xl.font-bold (t :settings-page/network-proxy)]
     [:div.p-2
      [:p [:label [:strong (t :type)]
           (ui/select [{:label "System" :value "system" :selected (= type "system")}
                       {:label "Direct" :value "direct" :selected (= type "direct")}
                       {:label "HTTP" :value "http" :selected (= type "http")}
                       {:label "SOCKS5" :value "socks5" :selected (= type "socks5")}]
                      (fn [_e value]
                        (set-opts! (assoc opts :type value :protocol value))))]]
      [:p.flex
       [:label.pr-4
        {:class (if disabled? "opacity-50" nil)}
        [:strong (t :host)]
        [:input.form-input.is-small
         {:value     (:host opts)
          :disabled  disabled?
          :on-change #(set-opts!
                       (assoc opts :host (util/trim-safe (util/evalue %))))}]]

       [:label
        {:class (if disabled? "opacity-50" nil)}
        [:strong (t :port)]
        [:input.form-input.is-small
         {:value     (:port opts) :type "number" :min 1 :max 65535
          :disabled  disabled?
          :on-change #(set-opts!
                       (assoc opts :port (util/trim-safe (util/evalue %))))}]]]

      [:hr]
      [:p.flex.items-center.space-x-2
       [:span.w-60
        [:input.form-input.is-small
         {:ref         *test-input
          :list        "proxy-test-url-datalist"
          :type        "url"
          :placeholder "https://"
          :on-change   #(set-opts!
                         (assoc opts :test (util/trim-safe (util/evalue %))))
          :value       (:test opts)}]
        [:datalist#proxy-test-url-datalist
         [:option "https://api.logseq.com/logseq/version"]
         [:option "https://logseq-connectivity-testing-prod.s3.us-east-1.amazonaws.com/logseq-connectivity-testing"]
         [:option "https://www.google.com"]
         [:option "https://s3.amazonaws.com"]
         [:option "https://clients3.google.com/generate_204"]]]

       (ui/button (if testing? (ui/loading "Testing") "Test URL")
                  :intent "logseq"
                  :on-click #(let [val (util/trim-safe (.-value (rum/deref *test-input)))]
                               (when (and (not testing?) (not (string/blank? val)))
                                 (set-testing?! true)
                                 (-> (p/let [result (ipc/ipc :testProxyUrl val opts)]
                                       (js->clj result :keywordize-keys true))
                                     (p/then (fn [{:keys [code response-ms]}]
                                               (notification/clear! :proxy-net-check)
                                               (notification/show! (str "Success! Status " code " in " response-ms "ms.") :success)))
                                     (p/catch (fn [e]
                                                (notification/show! (str e) :error false :proxy-net-check)))
                                     (p/finally (fn [] (set-testing?! false)))))))]

      [:p.pt-2
       (ui/button (t :save)
                  :on-click (fn []
                              (p/let [_ (ipc/ipc :setProxy opts)]
                                (state/set-state! [:electron/user-cfgs :settings/agent] opts))))]]]))

(rum/defc load-from-web-url-container
  []
  (let [[url set-url!] (rum/use-state "http://127.0.0.1:8080/")
        [pending? set-pending?] (rum/use-state false)
        handle-submit! (fn []
                         (set-pending? true)
                         (-> (plugin-handler/load-plugin-from-web-url! url)
                             (p/then #(do (notification/show! "New plugin registered!" :success)
                                          (shui/dialog-close!)))
                             (p/catch #(notification/show! (str %) :error))
                             (p/finally
                               #(set-pending? false))))]

    [:div.px-4.pt-4.pb-2.rounded-md.flex.flex-col.gap-2
     [:div.flex.flex-col.gap-3
      (shui/input {:placeholder "http://"
                   :value url
                   :on-change #(set-url! (-> (util/evalue %) (util/trim-safe)))
                   :auto-focus true})
      [:span.text-gray-10
       (shui/tabler-icon "info-circle" {:size 13})
       [:span "URLs support both GitHub repositories and local development servers.
      (For examples: https://github.com/xyhp915/logseq-journals-calendar,
      http://localhost:8080/<plugin-dir-root>)"]]]
     [:div.flex.justify-end
      (shui/button {:disabled (or pending? (string/blank? url))
                    :on-click handle-submit!}
                   (if pending? (ui/loading) "Install"))]]))

(rum/defc install-from-github-release-container
  []
  (let [[url set-url!] (rum/use-state "")
        [opts set-opts!] (rum/use-state {:theme? false :effect? false})
        [pending set-pending!] (rum/use-state false)
        *input (rum/use-ref nil)]
    [:div.p-4.flex.flex-col.pb-0
     (shui/input {:placeholder "GitHub repo url"
                  :value url
                  :ref *input
                  :on-change #(set-url! (util/evalue %))
                  :auto-focus true})
     [:div.flex.gap-6.pt-3.items-center.select-none
      [:label.flex.items-center.gap-2
       (shui/checkbox {:checked (:theme? opts)
                       :on-checked-change #(set-opts! (assoc opts :theme? %))})
       [:span.opacity-60 "theme?"]]
      [:label.flex.items-center.gap-2
       (shui/checkbox {:checked (:effect? opts)
                       :on-checked-change #(set-opts! (assoc opts :effect? %))})
       [:span.opacity-60 "effect?"]]]
     [:div.flex.justify-end.pt-3
      (shui/button
       {:on-click (fn []
                    (if (or (string/blank? (util/trim-safe url))
                            (not (string/starts-with? url "https://")))
                      (.focus (rum/deref *input))
                      (let [url (string/replace-first url "https://github.com/" "")
                            matched (re-find #"([^\/]+)/([^\/]+)" url)]
                        (if-let [id (some-> matched (nth 2))]
                          (do
                            (set-pending! true)
                            (-> #js {:id id :repo (first matched)
                                     :theme (:theme? opts)
                                     :effect (:effect? opts)}
                                (js/window.logseq.api.__install_plugin)
                                (p/then #(shui/dialog-close!))
                                (p/catch #(notification/show! (str %) :error))
                                (p/finally #(set-pending! false))))
                          (notification/show! "Invalid GitHub repo url" :error)))))
        :disabled pending}
       (if pending (ui/loading "Installing") "Install"))]]))

(rum/defc auto-check-for-updates-control
  []
  (let [[enabled, set-enabled!] (rum/use-state (plugin-handler/get-enabled-auto-check-for-updates?))
        text (t :plugin/auto-check-for-updates)]

    [:div.flex.items-center.justify-between.px-3.py-2
     {:on-click (fn []
                  (let [t (not enabled)]
                    (set-enabled! t)
                    (plugin-handler/set-enabled-auto-check-for-updates t)
                    (notification/show!
                     [:span text [:strong.pl-1 (if t "ON" "OFF")] "!"]
                     (if t :success :info))))}
     [:span.pr-3.opacity-80 text]
     (ui/toggle enabled #() true)]))

(rum/defc ^:large-vars/cleanup-todo panel-control-tabs < rum/static
  [search-key *search-key category *category
   sort-by *sort-by filter-by *filter-by total-nums
   selected-unpacked-pkg market? develop-mode?
   reload-market-fn agent-opts]

  (let [*search-ref (rum/create-ref)]
    [:div.pb-3.flex.justify-between.control-tabs.relative
     [:div.flex.items-center.l
      (category-tabs t total-nums category #(reset! *category %))

      (when (and develop-mode? (util/electron?) (not market?))
        [:div
         (ui/tooltip
          (ui/button
           (t :plugin/load-unpacked)
           {:icon "upload"
            :intent "link"
            :class "load-unpacked"
            :on-click plugin-handler/load-unpacked-plugin})
          [:div (t :plugin/unpacked-tips)])

         (when (util/electron?)
           (unpacked-plugin-loader selected-unpacked-pkg))])]

     [:div.flex.items-center.r
      ;; extra info
      (when-let [proxy-val (state/http-proxy-enabled-or-val?)]
        (ui/button
         [:span.flex.items-center.text-indigo-500
          (ui/icon "world-download") proxy-val]
         :small? true
         :intent "link"
         :on-click #(state/pub-event! [:go/proxy-settings agent-opts])))

      ;; search
      (panel-tab-search search-key *search-key *search-ref)

      ;; sorter & filter
      (let [aim-icon #(if (= filter-by %) "check" "circle")
            items (if market?
                    [{:title   (t :plugin/all)
                      :options {:on-click #(reset! *filter-by :default)}
                      :icon    (ui/icon (aim-icon :default))}

                     {:title   (t :plugin/installed)
                      :options {:on-click #(reset! *filter-by :installed)}
                      :icon    (ui/icon (aim-icon :installed))}

                     {:title   (t :plugin/not-installed)
                      :options {:on-click #(reset! *filter-by :not-installed)}
                      :icon    (ui/icon (aim-icon :not-installed))}]

                    [{:title   (t :plugin/all)
                      :options {:on-click #(reset! *filter-by :default)}
                      :icon    (ui/icon (aim-icon :default))}

                     {:title   (t :plugin/enabled)
                      :options {:on-click #(reset! *filter-by :enabled)}
                      :icon    (ui/icon (aim-icon :enabled))}

                     {:title   (t :plugin/disabled)
                      :options {:on-click #(reset! *filter-by :disabled)}
                      :icon    (ui/icon (aim-icon :disabled))}

                     {:title   (t :plugin/unpacked)
                      :options {:on-click #(reset! *filter-by :unpacked)}
                      :icon    (ui/icon (aim-icon :unpacked))}

                     {:title   (t :plugin/update-available)
                      :options {:on-click #(reset! *filter-by :update-available)}
                      :icon    (ui/icon (aim-icon :update-available))}])]
        (ui/button
         (ui/icon "filter")
         :class (str (when-not (contains? #{:default} filter-by) "picked ") "sort-or-filter-by")
         :on-click #(shui/popup-show! (.-target %)
                                      (fn [{:keys [id]}]
                                        (render-classic-dropdown-items id items))
                                      {:as-dropdown? true})
         :variant :ghost))

      (when market?
        (let [aim-icon #(if (= sort-by %) "check" "circle")
              items [{:title (t :plugin/popular)
                      :options {:on-click #(reset! *sort-by :default)}
                      :icon (ui/icon (aim-icon :default))}

                     {:title (t :plugin/downloads)
                      :options {:on-click #(reset! *sort-by :downloads)}
                      :icon (ui/icon (aim-icon :downloads))}

                     {:title (t :plugin/stars)
                      :options {:on-click #(reset! *sort-by :stars)}
                      :icon (ui/icon (aim-icon :stars))}

                     {:title (t :plugin/title "A - Z")
                      :options {:on-click #(reset! *sort-by :letters)}
                      :icon (ui/icon (aim-icon :letters))}]]

          (ui/button
           (ui/icon "arrows-sort")
           :class (str (when-not (contains? #{:default :popular} sort-by) "picked ") "sort-or-filter-by")
           :on-click #(shui/popup-show! (.-target %)
                                        (fn [{:keys [id]}]
                                          (render-classic-dropdown-items id items))
                                        {:as-dropdown? true})
           :variant :ghost)))

      ;; more - updater
      (let [items (concat (if market?
                            [{:title [:span.flex.items-center.gap-1 (ui/icon "rotate-clockwise") (t :plugin/refresh-lists)]
                              :options {:on-click #(reload-market-fn)}}]
                            [{:title [:span.flex.items-center.gap-1 (ui/icon "rotate-clockwise") (t :plugin/check-all-updates)]
                              :options {:on-click #(plugin-handler/user-check-enabled-for-updates! (not= :plugins category))}}])

                          (when (util/electron?)
                            [{:title   [:span.flex.items-center.gap-1 (ui/icon "world") (t :settings-page/network-proxy)]
                              :options {:on-click #(state/pub-event! [:go/proxy-settings agent-opts])}}

                             {:title   [:span.flex.items-center.gap-1 (ui/icon "arrow-down-circle") (t :plugin.install-from-file/menu-title)]
                              :options {:on-click plugin-config-handler/open-replace-plugins-modal}}])

                          [{:hr true}]

                          (when (state/developer-mode?)
                            (if (util/electron?)
                              [{:title [:span.flex.items-center.gap-1 (ui/icon "file-code") (t :plugin/open-preferences)]
                                :options {:on-click
                                          #(p/let [root (plugin-handler/get-ls-dotdir-root)]
                                             (js/apis.openPath (str root "/preferences.json")))}}
                               {:title [:span.flex.items-center.whitespace-nowrap.gap-1
                                        (ui/icon "bug") (t :plugin/open-logseq-dir) [:code "~/.logseq"]]
                                :options {:on-click
                                          #(p/let [root (plugin-handler/get-ls-dotdir-root)]
                                             (js/apis.openPath root))}}]
                              [{:title [:span.flex.items-center.whitespace-nowrap.gap-1
                                        (ui/icon "plug") (t :plugin/load-from-web-url)]
                                :options {:on-click
                                          #(shui/dialog-open! load-from-web-url-container)}}]))

                          [{:title [:span.flex.items-center.gap-1 (ui/icon "alert-triangle") (t :plugin/report-security)]
                            :options {:on-click #(plugin-handler/open-report-modal!)}}]

                          [{:hr true :key "dropdown-more"}
                           {:title (auto-check-for-updates-control)}])]

        (ui/button
         (ui/icon "dots-vertical")
         :class "more-do"
         :on-click #(shui/popup-show! (.-target %)
                                      (fn [{:keys [id]}]
                                        (render-classic-dropdown-items id items))
                                      {:as-dropdown? true
                                       :align "center"
                                       :content-props {:side-offset 10}})
         :variant :ghost))

      ;; developer
      (panel-tab-developer)]]))

(def plugin-items-list-mixins
  {:did-mount
   (fn [s]
     (when-let [^js el (rum/dom-node s)]
       (when-let [^js el-list (.querySelector el ".cp__plugins-item-lists")]
         (when-let [^js cls (.-classList (.querySelector el ".control-tabs"))]
           (.addEventListener
            el-list "scroll"
            #(if (> (.-scrollTop el-list) 1)
               (.add cls "scrolled")
               (.remove cls "scrolled"))))))
     s)})

(rum/defc lazy-items-loader
  [load-more!]
  (let [^js inViewState (ui/useInView #js {:threshold 0})
        in-view?        (.-inView inViewState)]

    (hooks/use-effect!
     (fn []
       (load-more!))
     [in-view?])

    [:div {:ref (.-ref inViewState)}
     [:p.py-1.text-center.opacity-0 (when (.-inView inViewState) "·")]]))

(defn weighted-sort-by
  [key pkgs]
  (let [default? (or (nil? key) (= key :default))
        grouped-pkgs (if default?
                       (some->> pkgs
                                (group-by (fn [{:keys [addedAt]}]
                                            (and (number? addedAt)
                                                 (< (- (js/Date.now) addedAt)
                                         ;; under 6 days
                                                    (* 1000 60 60 24 6)))))
                                (into {}))
                       {false pkgs})
        pinned-pkgs (get grouped-pkgs true)
        pkgs (get grouped-pkgs false)
        ;; calculate weight
        [key pkgs] (if default?
                     (let [decay-factor 0.001
                           download-weight 0.8
                           star-weight 0.2]
                       (letfn [(normalize [vals val]
                                 (let [min-val (apply min vals)
                                       max-val (apply max vals)]
                                   (if (= max-val min-val) 0
                                       (/ (- val min-val) (- max-val min-val)))))
                               (time-diff-in-days [ts]
                                 (when-let [time-diff (and (number? ts) (- (js/Date.now) ts))]
                                   (/ time-diff (* 1000 60 60 24))))]
                         [:weight
                          (let [all-downloads (->> (map :downloads pkgs) (remove #(not (number? %))))
                                all-stars (->> (map :stars pkgs) (remove #(not (number? %))))]
                            (->> pkgs
                                 (map (fn [{:keys [downloads stars latestAt] :as pkg}]
                                        (let [downloads (if (number? downloads) downloads 1)
                                              stars (if (number? stars) stars 1)
                                              days-since-latest (time-diff-in-days latestAt)
                                              decay (js/Math.exp (* -1 decay-factor days-since-latest))
                                              normalized-downloads (normalize all-downloads downloads)
                                              normalize-stars (normalize all-stars stars)
                                              download-score (* normalized-downloads download-weight)
                                              star-score (* normalize-stars star-weight)]
                                          (assoc pkg :weight (+ download-score star-score decay)))))))]))
                     [key pkgs])]
    (->> (apply sort-by
                (conj
                 (case key
                   :letters [#(util/safe-lower-case (or (:title %) (:name %)))]
                   [key #(compare %2 %1)])
                 pkgs))
         (concat pinned-pkgs))))

(rum/defcs ^:large-vars/data-var marketplace-plugins
  < rum/static rum/reactive
  plugin-items-list-mixins
  (rum/local false ::fetching)
  (rum/local "" ::search-key)
  (rum/local :plugins ::category)
  (rum/local :default ::sort-by)        ;; default (weighted) / downloads / stars / letters / updates
  (rum/local :default ::filter-by)
  (rum/local nil ::error)
  (rum/local nil ::cached-query-flag)
  (rum/local 1 ::current-page)
  {:did-mount
   (fn [s]
     (let [reload-fn (fn [force-refresh?]
                       (when-not @(::fetching s)
                         (reset! (::fetching s) true)
                         (reset! (::error s) nil)
                         (-> (plugin-handler/load-marketplace-plugins force-refresh?)
                             (p/then #(plugin-handler/load-marketplace-stats false))
                             (p/catch #(do (js/console.error %) (reset! (::error s) %)))
                             (p/finally #(reset! (::fetching s) false)))))]
       (reload-fn false)
       (assoc s ::reload (partial reload-fn true))))}
  [state]
  (let [*list-node-ref     (rum/create-ref)
        pkgs               (state/sub :plugin/marketplace-pkgs)
        stats              (state/sub :plugin/marketplace-stats)
        installed-plugins  (state/sub :plugin/installed-plugins)
        installing         (state/sub :plugin/installing)
        online?            (state/sub :network/online?)
        develop-mode?      (state/sub :ui/developer-mode?)
        agent-opts         (state/sub [:electron/user-cfgs :settings/agent])
        *search-key        (::search-key state)
        *category          (::category state)
        *sort-by           (::sort-by state)
        *filter-by         (::filter-by state)
        *cached-query-flag (::cached-query-flag state)
        *current-page      (::current-page state)
        *fetching          (::fetching state)
        *error             (::error state)
        theme-plugins      (filter #(:theme %) pkgs)
        normal-plugins     (filter #(not (:theme %)) pkgs)
        filtered-pkgs      (when (seq pkgs)
                             (if (= @*category :themes) theme-plugins normal-plugins))
        total-nums         [(count normal-plugins) (count theme-plugins)]
        filtered-pkgs      (if (and (seq filtered-pkgs) (not= :default @*filter-by))
                             (filter #(apply
                                       (if (= :installed @*filter-by) identity not)
                                       [(contains? installed-plugins (keyword (:id %)))])
                                     filtered-pkgs)
                             filtered-pkgs)
        filtered-pkgs      (if-not (string/blank? @*search-key)
                             (if-let [author (and (string/starts-with? @*search-key "@")
                                                  (subs @*search-key 1))]
                               (filter #(= author (:author %)) filtered-pkgs)
                               (search/fuzzy-search
                                filtered-pkgs @*search-key
                                :limit 30
                                :extract-fn :title))
                             filtered-pkgs)
        filtered-pkgs      (map #(if-let [stat (get stats (keyword (:id %)))]
                                   (let [downloads (:total_downloads stat)
                                         stars     (:stargazers_count stat)
                                         latest-at (some-> (:updated_at stat) (js/Date.) (.getTime))]
                                     (assoc %
                                            :stat stat
                                            :stars stars
                                            :latestAt latest-at
                                            :downloads downloads))
                                   %) filtered-pkgs)
        sorted-plugins     (weighted-sort-by @*sort-by filtered-pkgs)

        fn-query-flag      (fn [] (string/join "_" (map #(str @%) [*filter-by *sort-by *search-key *category])))
        str-query-flag     (fn-query-flag)
        _                  (when (not= str-query-flag @*cached-query-flag)
                             (when-let [^js list-cnt (rum/deref *list-node-ref)]
                               (set! (.-scrollTop list-cnt) 0))
                             (reset! *current-page 1))
        _                  (reset! *cached-query-flag str-query-flag)

        page-total-items   (count sorted-plugins)
        sorted-plugins     (if-not (> page-total-items PER-PAGE-SIZE)
                             sorted-plugins (take (* @*current-page PER-PAGE-SIZE) sorted-plugins))
        load-more-pages!   #(when (> page-total-items PER-PAGE-SIZE)
                              (when (< (* PER-PAGE-SIZE @*current-page) page-total-items)
                                (reset! *current-page (inc @*current-page))))]

    [:div.cp__plugins-marketplace

     (panel-control-tabs
      @*search-key *search-key
      @*category *category
      @*sort-by *sort-by @*filter-by *filter-by
      total-nums nil true develop-mode? (::reload state)
      agent-opts)

     (cond
       (not online?)
       [:p.flex.justify-center.pt-20.opacity-50 (svg/offline 30)]

       @*fetching
       [:p.flex.justify-center.py-20 svg/loading]

       @*error
       [:p.flex.justify-center.pt-20.opacity-50 (t :plugin/remote-error) (.-message @*error)]

       :else
       [:div.cp__plugins-marketplace-cnt
        {:class (util/classnames [{:has-installing (boolean installing)}])}
        [:div.cp__plugins-item-lists
         {:ref *list-node-ref}
         [:div.cp__plugins-item-lists-inner
          ;; items list
          (for [item sorted-plugins]
            (rum/with-key
              (let [pid  (keyword (:id item))
                    stat (:stat item)]
                (plugin-item-card t item
                                  (get-in item [:settings :disabled]) true *search-key installing
                                  (and installing (= (keyword (:id installing)) pid))
                                  (contains? installed-plugins pid) stat nil))
              (:id item)))]

         ;; items loader
         (when (seq sorted-plugins)
           (lazy-items-loader load-more-pages!))]])]))

(rum/defcs ^:large-vars/data-var installed-plugins
  < rum/static rum/reactive
  plugin-items-list-mixins
  (rum/local "" ::search-key)
  (rum/local :default ::filter-by)                        ;; default / enabled / disabled / unpacked / update-available
  (rum/local :default ::sort-by)
  (rum/local :plugins ::category)
  (rum/local nil ::cached-query-flag)
  (rum/local 1 ::current-page)
  [state]
  (let [*list-node-ref        (rum/create-ref)
        installed-plugins'    (vals (state/sub [:plugin/installed-plugins]))
        updating              (state/sub :plugin/installing)
        develop-mode?         (state/sub :ui/developer-mode?)
        selected-unpacked-pkg (state/sub :plugin/selected-unpacked-pkg)
        coming-updates        (state/sub :plugin/updates-coming)
        agent-opts            (state/sub [:electron/user-cfgs :settings/agent])
        *filter-by            (::filter-by state)
        *sort-by              (::sort-by state)
        *search-key           (::search-key state)
        *category             (::category state)
        *cached-query-flag    (::cached-query-flag state)
        *current-page         (::current-page state)
        default-filter-by?    (= :default @*filter-by)
        theme-plugins         (filter #(:theme %) installed-plugins')
        normal-plugins        (filter #(not (:theme %)) installed-plugins')
        filtered-plugins      (when (seq installed-plugins')
                                (if (= @*category :themes) theme-plugins normal-plugins))
        total-nums            [(count normal-plugins) (count theme-plugins)]
        filtered-plugins      (if-not default-filter-by?
                                (filter (fn [it]
                                          (let [disabled (get-in it [:settings :disabled])]
                                            (case @*filter-by
                                              :enabled (not disabled)
                                              :disabled disabled
                                              :unpacked (not (:iir it))
                                              :update-available (state/plugin-update-available? (:id it))
                                              true))) filtered-plugins)
                                filtered-plugins)
        filtered-plugins      (if-not (string/blank? @*search-key)
                                (if-let [author (and (string/starts-with? @*search-key "@")
                                                     (subs @*search-key 1))]
                                  (filter #(= author (:author %)) filtered-plugins)
                                  (search/fuzzy-search
                                   filtered-plugins @*search-key
                                   :limit 30
                                   :extract-fn :name))
                                filtered-plugins)
        sorted-plugins        (if default-filter-by?
                                (->> filtered-plugins
                                     (reduce #(let [disabled? (get-in %2 [:settings :disabled])
                                                    old-dirty (get @*dirties-toggle-items (keyword (:id %2)))
                                                    k         (if (if (boolean? old-dirty) (not old-dirty) disabled?) 1 0)]
                                                (update %1 k conj %2)) [[] []])
                                     (#(update % 0 (fn [coll] (sort-by :iir coll))))
                                     (flatten))
                                (do
                                  (clear-dirties-states!)
                                  filtered-plugins))

        fn-query-flag         (fn [] (string/join "_" (map #(str @%) [*filter-by *sort-by *search-key *category])))
        str-query-flag        (fn-query-flag)
        _                     (when (not= str-query-flag @*cached-query-flag)
                                (when-let [^js list-cnt (rum/deref *list-node-ref)]
                                  (set! (.-scrollTop list-cnt) 0))
                                (reset! *current-page 1))
        _                     (reset! *cached-query-flag str-query-flag)

        page-total-items      (count sorted-plugins)
        sorted-plugins        (if-not (> page-total-items PER-PAGE-SIZE)
                                sorted-plugins (take (* @*current-page PER-PAGE-SIZE) sorted-plugins))
        load-more-pages!      #(when (> page-total-items PER-PAGE-SIZE)
                                 (when (< (* PER-PAGE-SIZE @*current-page) page-total-items)
                                   (reset! *current-page (inc @*current-page))))]

    [:div.cp__plugins-installed
     (panel-control-tabs
      @*search-key *search-key
      @*category *category
      @*sort-by *sort-by
      @*filter-by *filter-by
      total-nums selected-unpacked-pkg
      false develop-mode? nil
      agent-opts)

     [:div.cp__plugins-item-lists.pb-6
      {:ref *list-node-ref}
      [:div.cp__plugins-item-lists-inner
       (for [item sorted-plugins]
         (rum/with-key
           (let [pid (keyword (:id item))]
             (plugin-item-card t item
                               (get-in item [:settings :disabled]) false *search-key updating
                               (and updating (= (keyword (:id updating)) pid))
                               true nil (get coming-updates pid)))
           (:id item)))]

      (if (seq sorted-plugins)
        (lazy-items-loader load-more-pages!)
        [:div.flex.items-center.justify-center.py-28.flex-col.gap-2.opacity-30
         (shui/tabler-icon "list-search" {:size 40})
         [:span.text-sm "Nothing Found."]])]]))

(rum/defcs waiting-coming-updates
  < rum/reactive
  {:will-mount (fn [s] (state/reset-unchecked-update) s)}
  [_s]
  (let [_            (state/sub :plugin/updates-coming)
        downloading? (state/sub :plugin/updates-downloading?)
        unchecked    (state/sub :plugin/updates-unchecked)
        updates      (state/all-available-coming-updates)]

    [:div.cp__plugins-waiting-updates
     [:h1.mb-4.text-2xl.p-1 (t :plugin/found-n-updates (count updates))]

     (if (seq updates)
       ;; lists
       [:ul
        {:class (when downloading? "downloading")}
        (for [it updates
              :let [k     (str "lsp-it-" (:id it))
                    c?    (not (contains? unchecked (:id it)))
                    notes (util/trim-safe (:latest-notes it))]]
          [:li.flex.items-center
           {:key   k
            :class (when c? "checked")}

           [:label.flex-1
            {:for k}
            (shui/checkbox
             {:id k
              :default-checked c?
              :on-checked-change (fn [checked?]
                                   (when-not downloading?
                                     (state/set-unchecked-update (:id it) (not checked?))))})
            [:strong.px-3 (:title it)
             [:sup (str (:version it) " 👉 " (:latest-version it))]]]

           [:div.px-4
            (when-not (string/blank? notes)
              (ui/tooltip [:span.opacity-30.hover:opacity-80 (ui/icon "info-circle")] [:p notes]))]])]

       ;; all done
       [:div.py-4 [:strong.text-4xl (str "\uD83C\uDF89 " (t :plugin/all-updated))]])

     ;; actions
     (when (seq updates)
       [:div.pt-5.flex.justify-end
        (ui/button
         (if downloading?
           [:span (ui/loading (t :plugin/updates-downloading))]
           [:span.flex.items-center (ui/icon "download") (t :plugin/update-all-selected)])

         :on-click
         #(when-not downloading?
            (plugin-handler/open-updates-downloading)
            (if-let [n (state/get-next-selected-coming-update)]
              (plugin-handler/check-or-update-marketplace-plugin!
               (assoc n :only-check false)
               (fn [^js e] (notification/show! (.toString e) :error)))
              (plugin-handler/close-updates-downloading)))

         :disabled
         (or downloading?
             (and (seq unchecked)
                  (= (count unchecked) (count updates)))))])]))

(rum/defc plugins-from-file
  < rum/reactive
  [plugins]
  [:div.cp__plugins-fom-file
   [:h1.mb-4.text-2xl.p-1 (t :plugin.install-from-file/title)]
   (if (seq plugins)
     [:div
      [:div.mb-2.text-xl (t :plugin.install-from-file/notice)]
      ;; lists
      [:ul
       (for [it (:install plugins)
             :let [k (str "lsp-it-" (name (:id it)))]]
         [:li.flex.items-center
          {:key k}
          [:label.flex-1
           {:for k}
           [:strong.px-3 (str (name (:id it)) " " (:version it))]]])]

      ;; actions
      [:div.pt-5
       (ui/button [:span (t :plugin/install)]
                  :on-click #(do
                               (plugin-config-handler/replace-plugins plugins)
                               (shui/dialog-close! "ls-plugins-from-file-modal")))]]
     ;; all done
     [:div.py-4 [:strong.text-xl (str "\uD83C\uDF89 " (t :plugin.install-from-file/success))]])])

(defn open-select-theme!
  []
  (shui/dialog-open! installed-themes
                     {:align :top}))

(rum/defc hook-ui-slot
  ([type payload] (hook-ui-slot type payload nil #(plugin-handler/hook-plugin-app type % nil)))
  ([type payload opts callback]
   (let [rs      (util/rand-str 8)
         id      (str "slot__" rs)
         *el-ref (rum/use-ref nil)]

     (hooks/use-effect!
      (fn []
        (let [timer (js/setTimeout #(callback {:type type :slot id :payload payload}) 50)]
          #(js/clearTimeout timer)))
      [id])

     (hooks/use-effect!
      (fn []
        (let [el (rum/deref *el-ref)]
          #(when-let [uis (seq (.querySelectorAll el "[data-injected-ui]"))]
             (doseq [^js el uis]
               (when-let [id (.-injectedUi (.-dataset el))]
                 (js/LSPluginCore._forceCleanInjectedUI id))))))
      [])

     [:div.lsp-hook-ui-slot
      (merge opts {:id            id
                   :ref           *el-ref
                   :on-pointer-down (fn [e] (util/stop-propagation e))})])))

(rum/defc hook-block-slot < rum/static
  [type block]
  (hook-ui-slot type {} nil #(plugin-handler/hook-plugin-block-slot block %)))

(rum/defc ui-item-renderer
  [pid type {:keys [key template prefix]}]
  (let [*el    (rum/use-ref nil)
        uni    #(str prefix "injected-ui-item-" %)
        ^js pl (js/LSPluginCore.registeredPlugins.get (name pid))]

    (hooks/use-effect!
     (fn []
       (when-let [^js el (rum/deref *el)]
         (js/LSPlugin.pluginHelpers.setupInjectedUI.call
          pl #js {:slot (.-id el) :key key :template template} #js {})))
     [template])

    (if-not (nil? pl)
      [:div
       {:id    (uni (str (name key) "-" (name pid)))
        :title key
        :class (uni (name type))
        :ref   *el}]
      [:<>])))

(rum/defc toolbar-plugins-manager-list
  [updates-coming items]
  (let [badge-updates? (and (not (plugin-handler/get-auto-checking?))
                            (seq (state/all-available-coming-updates updates-coming)))
        items (fn []
                (->> (concat
                      (for [[_ {:keys [key pinned?] :as opts} pid] items
                            :let [pkey (str (name pid) ":" key)]]
                        {:title key
                         :item [:div.flex.items-center.item-wrap
                                (ui-item-renderer pid :toolbar (assoc opts :prefix "pl-" :key (str "pl-" key)))
                                [:span {:style {:padding-left "2px"}} key]
                                [:span.pin.flex.items-center.opacity-60
                                 {:class (util/classnames [{:pinned pinned?}])}
                                 (ui/icon (if pinned? "pinned" "pin"))]]
                         :options {:on-click (fn [^js e]
                                               (let [^js target (.-target e)
                                                     user-btn? (boolean (.closest target "div[data-injected-ui]"))]
                                                 (when-not user-btn?
                                                   (plugin-handler/op-pinned-toolbar-item! pkey (if pinned? :remove :add)))
                                                 true))}})
                      [{:hr true}
                       {:title (t :plugins)
                        :options {:on-click #(plugin-handler/goto-plugins-dashboard!)
                                  :class "extra-item mt-2"}
                        :icon (ui/icon "apps")}

                       {:title (t :themes)
                        :options {:on-click #(plugin-handler/show-themes-modal!)
                                  :class "extra-item"}
                        :icon (ui/icon "palette")}

                       {:title (t :settings)
                        :options {:on-click #(plugin-handler/goto-plugins-settings!)
                                  :class "extra-item"}
                        :icon (ui/icon "adjustments")}

                       (when badge-updates?
                         {:title [:div.flex.items-center.space-x-5.leading-none
                                  [:span (t :plugin/found-updates)] (ui/point "bg-red-700" 5 {:style {:margin-top 2}})]
                          :options {:on-click #(open-waiting-updates-modal!)
                                    :class "extra-item"}
                          :icon (ui/icon "download")})]

                      [{:hr true :key "dropdown-more"}
                       {:title (auto-check-for-updates-control)}])
                     (remove nil?)))]

    [:div.toolbar-plugins-manager
     {:on-pointer-down
      (fn [^js e]
        (shui/popup-show! (.-target e)
                          (fn [{:keys [id]}]
                            (render-classic-dropdown-items id (items)))
                          {:as-dropdown? true
                           :content-props {:class "toolbar-plugins-manager-content"}}))}

     (shui/button-ghost-icon :puzzle
                             {:class "flex relative toolbar-plugins-manager-trigger"}
                             (when badge-updates?
                               (ui/point "bg-red-600.top-1.right-1.absolute" 4 {:style {:margin-right 2 :margin-top 2}})))]))

(rum/defc header-ui-items-list-wrap
  [children]
  (let [*wrap-el (rum/use-ref nil)
        [right-sidebar-resized] (rum-utils/use-atom ui-handler/*right-sidebar-resized-at)]

    (hooks/use-effect!
     (fn []
       (when-let [^js wrap-el (rum/deref *wrap-el)]
         (when-let [^js header-el (.closest wrap-el ".cp__header")]
           (let [^js header-l (.querySelector header-el "* > .l")
                 ^js header-r (.querySelector header-el "* > .r")
                 set-max-width! #(when (number? %) (set! (.-maxWidth (.-style wrap-el)) (str % "px")))
                 calc-wrap-max-width #(let [width-l  (.-offsetWidth header-l)
                                            width-t  (-> (js/document.querySelector "#main-content-container") (.-offsetWidth))
                                            children (to-array (.-children header-r))
                                            width-c' (reduce (fn [acc ^js e]
                                                               (when (some-> e (.-classList) (.contains "ui-items-container") (not))
                                                                 (+ acc (or (.-offsetWidth e) 0)))) 0 children)]
                                        (when-let [width-t (and (number? width-t)
                                                                (if-not (state/get-left-sidebar-open?)
                                                                  (- width-t width-l) width-t))]
                                          (set-max-width! (max (- width-t width-c' 100) 76))))]
             (.addEventListener js/window "resize" calc-wrap-max-width)
             (js/setTimeout calc-wrap-max-width 16)
             #(.removeEventListener js/window "resize" calc-wrap-max-width)))))
     [right-sidebar-resized])

    [:div.list-wrap
     {:ref *wrap-el}
     children]))

(rum/defcs hook-ui-items < rum/reactive
  < {:key-fn #(identity "plugin-hook-items")}
  "type of :toolbar, :pagebar"
  [_state type]
  (when (state/sub [:plugin/installed-ui-items])
    (let [toolbar?     (= :toolbar type)
          pinned-items (state/sub [:plugin/preferences :pinnedToolbarItems])
          pinned-items (and (sequential? pinned-items) (into #{} pinned-items))
          items        (state/get-plugins-ui-items-with-type type)
          items        (sort-by #(:key (second %)) items)]

      (when-let [items (and (seq items)
                            (if toolbar?
                              (map #(assoc-in % [1 :pinned?]
                                              (let [[_ {:keys [key]} pid] %
                                                    pkey (str (name pid) ":" key)]
                                                (contains? pinned-items pkey)))
                                   items)
                              items))]

        [:div.ui-items-container
         {:data-type (name type)}

         [:<>
          (header-ui-items-list-wrap
           (for [[_ {:keys [key pinned?] :as opts} pid] items]
             (when (or (not toolbar?)
                       (not (set? pinned-items)) pinned?)
               (rum/with-key (ui-item-renderer pid type opts) key))))

          ;; manage plugin buttons
          (when toolbar?
            (let [updates-coming (state/sub :plugin/updates-coming)]
              (toolbar-plugins-manager-list updates-coming items)))]]))))

(rum/defc hook-ui-fenced-code
  [block content {:keys [render edit] :as _opts}]

  (let [[content1 set-content1!] (rum/use-state content)
        [editor-active? set-editor-active!] (rum/use-state (string/blank? content))
        *cm (rum/use-ref nil)
        *el (rum/use-ref nil)]

    (hooks/use-effect!
     #(set-content1! content)
     [content])

    (hooks/use-effect!
     (fn []
       (some-> (rum/deref *el)
               (.closest ".ui-fenced-code-wrap")
               (.-classList)
               (#(if editor-active?
                   (.add % "is-active")
                   (.remove % "is-active"))))
       (when-let [cm (rum/deref *cm)]
         (.refresh cm)
         (.focus cm)
         (.setCursor cm (.lineCount cm) (count (.getLine cm (.lastLine cm))))))
     [editor-active?])

    (hooks/use-effect!
     (fn []
       (let [t (js/setTimeout
                #(when-let [^js cm (some-> (rum/deref *el)
                                           (.closest ".ui-fenced-code-wrap")
                                           (.querySelector ".CodeMirror")
                                           (.-CodeMirror))]
                   (rum/set-ref! *cm cm)
                   (doto cm
                     (.on "change" (fn []
                                     (some-> cm (.getDoc) (.getValue) (set-content1!))))))
                  ;; wait for the cm loaded
                1000)]
         #(js/clearTimeout t)))
     [])

    [:div.ui-fenced-code-result
     {:on-pointer-down (fn [e] (when (false? edit) (util/stop e)))
      :class         (util/classnames [{:not-edit (false? edit)}])
      :ref           *el}
     [:<>
      [:span.actions
       {:on-pointer-down #(util/stop %)}
       (ui/button (ui/icon "square-toggle-horizontal" {:size 14})
                  :on-click #(set-editor-active! (not editor-active?)))
       (ui/button (ui/icon "source-code" {:size 14})
                  :on-click #(editor-handler/edit-block! block (count content1)))]
      (when (fn? render)
        (js/React.createElement render #js {:content content1}))]]))

(rum/defc plugins-page
  []

  (let [[active set-active!] (rum/use-state :installed)
        market? (= active :marketplace)
        *el-ref (rum/create-ref)]

    (hooks/use-effect!
     (fn []
       (state/load-app-user-cfgs)
       #(clear-dirties-states!))
     [])

    (hooks/use-effect!
     #(clear-dirties-states!)
     [market?])

    [:div.cp__plugins-page
     {:ref *el-ref
      :class (when-not (util/electron?) "web-platform")
      :tab-index "-1"}

     [:h1 (t :plugins)]

     (when (util/electron?)
       [:<>
        (security-warning)
        [:hr.my-4]])

     [:div.tabs.flex.items-center.justify-center
      [:div.tabs-inner.flex.items-center
       (shui/button {:on-click #(set-active! :installed)
                     :class (when (not market?) "active")
                     :size :sm
                     :variant :text}
                    (t :plugin/installed))

       (shui/button {:on-click #(set-active! :marketplace)
                     :class (when market? "active")
                     :size :sm
                     :variant :text}
                    (shui/tabler-icon "apps")
                    (t :plugin/marketplace))]]

     [:div.panels
      (if market?
        (marketplace-plugins)
        (installed-plugins))]]))

(def *updates-sub-content-timer (atom nil))
(def *updates-sub-content (atom nil))

(defn set-updates-sub-content!
  [content duration]
  (reset! *updates-sub-content content)

  (when (> duration 0)
    (some-> @*updates-sub-content-timer (js/clearTimeout))
    (->> (js/setTimeout #(reset! *updates-sub-content nil) duration)
         (reset! *updates-sub-content-timer))))

(rum/defc updates-notifications-impl
  [check-pending? auto-checking? online?]
  (let [[uid, set-uid] (rum/use-state nil)
        [sub-content, _set-sub-content!] (rum-utils/use-atom *updates-sub-content)
        notify! (fn [content status]
                  (if auto-checking?
                    (println (t :plugin/list-of-updates) content)
                    (let [cb #(plugin-handler/cancel-user-checking!)]
                      (try
                        (set-uid (notification/show! content status false uid nil cb))
                        (catch js/Error _
                          (set-uid (notification/show! content status false nil nil cb)))))))]

    (hooks/use-effect!
     (fn []
       (if check-pending?
         (notify!
          [:div
           [:div (t :plugin/checking-for-updates)]
           (when sub-content [:p.opacity-60 sub-content])]
          (ui/loading ""))
         (when uid (notification/clear! uid))))
     [check-pending? sub-content])

    (hooks/use-effect!
      ;; scheduler for auto updates
     (fn []
       (when online?
         (let [last-updates (storage/get :lsp-last-auto-updates)]
           (when (and (not (false? last-updates))
                      (or (true? last-updates)
                          (not (number? last-updates))
                           ;; interval 12 hours
                          (> (- (js/Date.now) last-updates) (* 60 60 12 1000))))
             (let [update-timer (js/setTimeout
                                 (fn []
                                   (plugin-handler/auto-check-enabled-for-updates!)
                                   (storage/set :lsp-last-auto-updates (js/Date.now)))
                                 (if (util/electron?) 3000 (* 60 1000)))]
               #(js/clearTimeout update-timer))))))
     [online?])

    [:<>]))

(rum/defcs updates-notifications < rum/reactive
  [_]
  (let [updates-pending (state/sub :plugin/updates-pending)
        online?         (state/sub :network/online?)
        auto-checking?  (state/sub :plugin/updates-auto-checking?)
        check-pending?  (boolean (seq updates-pending))]
    (updates-notifications-impl check-pending? auto-checking? online?)))

(rum/defcs focused-settings-content
  < rum/reactive
  (rum/local (state/sub :plugin/focused-settings) ::cache)
  [_state title]
  (let [*cache  (::cache _state)
        focused (state/sub :plugin/focused-settings)
        nav?    (state/sub :plugin/navs-settings?)
        _       (state/sub :plugin/installed-plugins)
        _       (js/setTimeout #(reset! *cache focused) 100)]

    [:div.cp__plugins-settings.cp__settings-main
     [:div.cp__settings-inner.md:flex
      {:class (util/classnames [{:no-aside (not nav?)}])}
      (when nav?
        [:aside.md:w-64 {:style {:min-width "10rem"}}
         [:header.cp__settings-header
          [:h1.cp__settings-modal-title (or title (t :settings-of-plugins))]]
         (let [plugins (plugin-handler/get-enabled-plugins-if-setting-schema)]
           [:ul.settings-plugin-list
            (for [{:keys [id name title icon]} plugins]
              [:li
               {:key id :class (util/classnames [{:active (= id focused)}])}
               [:a.flex.items-center.settings-plugin-item
                {:data-id  id
                 :on-click #(do (state/set-state! :plugin/focused-settings id))}
                (if (and icon (not (string/blank? icon)))
                  [:img.icon {:src icon}]
                  svg/folder)
                [:strong.flex-1 (or title name)]]])])])

      [:article
       [:div.panel-wrap
        {:data-id focused}
        (when-let [^js pl (and focused (= @*cache focused)
                               (plugin-handler/get-plugin-inst focused))]
          (ui/catch-error
           [:p.warning.text-lg.mt-5 "Settings schema Error!"]
           (plugins-settings/settings-container
            (bean/->clj (.-settingsSchema pl)) pl)))]]]]))

(rum/defc custom-js-installer
  [{:keys [t current-repo db-restoring?]}]
  (hooks/use-effect!
   (fn []
     (when (and (not db-restoring?)
                (not util/nfs?))
       (ui-handler/exec-js-if-exists-&-allowed! t)))
   [current-repo db-restoring?])
  nil)

(rum/defc perf-tip-content
  [pid name url]
  [:div
   [:span.block.whitespace-normal
    "This plugin "
    [:strong.text-error "#" name]
    " takes too long to load, affecting the application startup time and
     potentially causing other plugins to fail to load."]

   [:path.opacity-50
    [:small [:span.pr-1 (ui/icon "folder")] url]]

   [:p
    (ui/button "Disable now"
               :small? true
               :on-click
               (fn []
                 (-> (js/LSPluginCore.disable pid)
                     (p/then #(do
                                (notification/clear! pid)
                                (notification/show!
                                 [:span "The plugin "
                                  [:strong.text-error "#" name]
                                  " is disabled."] :success
                                 true nil 3000 nil)))
                     (p/catch #(js/console.error %)))))]])

(defn open-plugins-modal!
  []
  (shui/dialog-open!
   (plugins-page)
   {:label :plugins-dashboard
    :align :start}))

(defn open-waiting-updates-modal!
  []
  (shui/dialog-open!
   (fn []
     (waiting-coming-updates))
   {:center? true}))

(defn open-plugins-from-file-modal!
  [plugins]
  (shui/dialog-open!
   (fn []
     (plugins-from-file plugins))
   {:id "ls-plugins-from-file-modal"}))

(defn open-focused-settings-modal!
  [title]
  (shui/dialog-open!
   (fn []
     [:div.settings-modal.of-plugins
      (focused-settings-content title)])
   {:label   "plugin-settings-modal"
    :align   :start
    :id      "ls-focused-settings-modal"}))

(defn hook-custom-routes
  [routes]
  (cond-> routes
    config/lsp-enabled?
    (concat (some->> (plugin-handler/get-route-renderers)
                     (mapv #(when-let [{:keys [name path render]} %]
                              (when (not (string/blank? path))
                                [path {:name name :view (fn [r] (render r %))}])))
                     (remove nil?)))))

(defn hook-daemon-renderers
  []
  (when-let [rs (seq (plugin-handler/get-daemon-renderers))]
    [:div.lsp-daemon-container.fixed.z-10
     (for [{:keys [key _pid render]} rs]
       (when (fn? render)
         [:div.lsp-daemon-container-card {:data-key key} (render)]))]))
