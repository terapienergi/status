(ns status-im.chat.models.loading
  (:require [re-frame.core :as re-frame]
            [status-im.constants :as constants]
            [status-im.ui.screens.chat.state :as chat.state]
            [status-im.data-store.chats :as data-store.chats]
            [status-im.data-store.messages :as data-store.messages]
            [status-im.mailserver.core :as mailserver]
            [status-im.utils.fx :as fx]
            [status-im.chat.models.reactions :as reactions]
            [status-im.chat.models.message-list :as message-list]
            [taoensso.timbre :as log]
            [status-im.chat.models.message-seen :as message-seen]
            [status-im.chat.models.mentions :as mentions]))

(defn cursor->clock-value
  [^js cursor]
  (js/parseInt (.substring cursor 51 64)))

(defn clock-value->cursor [clock-value]
  (str "000000000000000000000000000000000000000000000000000" clock-value "0x0000000000000000000000000000000000000000000000000000000000000000"))

(fx/defn update-chats-in-app-db
  {:events [:chats-list/load-success]}
  [{:keys [db] :as cofx} new-chats]
  (let [old-chats (:chats db)
        chats (reduce (fn [acc {:keys [chat-id] :as chat}]
                        (assoc acc chat-id chat))
                      {}
                      new-chats)
        chats (merge old-chats chats)]
    {:db (assoc db :chats chats
                :chats/loading? false)}))

(fx/defn handle-chat-visibility-changed
  {:events [:chat.ui/message-visibility-changed]}
  [{:keys [db] :as cofx} ^js event]
  (let [^js viewable-items (.-viewableItems event)
        ^js last-element (aget viewable-items (dec (.-length viewable-items)))]
    (println "MESSAGE VISIBILITY CHANGED")
    (when last-element
      (let [last-element-clock-value (:clock-value (.-item last-element))
            chat-id (:chat-id (.-item last-element))]
        (when (and last-element-clock-value
                   (get-in db [:pagination-info chat-id :messages-initialized?]))
          (let [new-messages (reduce-kv (fn [acc message-id {:keys [clock-value] :as v}]
                                          (if (<= last-element-clock-value clock-value)
                                            (assoc acc message-id v)
                                            acc))
                                        {}
                                        (get-in db [:messages chat-id]))]
            {:db (-> db
                     (assoc-in [:messages chat-id] new-messages)
                     (assoc-in [:pagination-info chat-id] {:all-loaded? false
                                                           :messages-initialized? true
                                                           :cursor (clock-value->cursor last-element-clock-value)})
                     (assoc-in [:message-lists chat-id] (message-list/add-many nil (vals new-messages))))}))))))

(fx/defn handle-chat-visibility-changed2
  {:events [:chat.ui/message-visibility-changed2]}
  [cofx ^js event]
  (when-let [^js viewable-items (.-viewableItems event)]
    (let [{:keys [unviewed-messages-ids chat-id]}
          (reduce (fn [acc item]
                    (let [{:keys [message-id chat-id seen content]} (.-item item)]
                      ;(println "wtf " (:text content) seen)
                      (cond-> acc
                              (and (nil? (:chat-id acc)) chat-id)
                              (assoc :chat-id chat-id)
                              (and message-id (not seen))
                              (update :unviewed-messages-ids conj message-id))))
                  {:unviewed-messages-ids '() :chat-id nil}
                  viewable-items)]
      ;(println chat-id (count unviewed-messages-ids))
      (when (and chat-id (seq unviewed-messages-ids))
        (message-seen/mark-messages-seen cofx chat-id unviewed-messages-ids)))))

(fx/defn initialize-chats
  "Initialize persisted chats on startup"
  [cofx]
  (data-store.chats/fetch-chats-rpc cofx {:on-success
                                          #(re-frame/dispatch
                                            [:chats-list/load-success %])}))

(fx/defn handle-failed-loading-messages
  {:events [::failed-loading-messages]}
  [{:keys [db]} current-chat-id _ err]
  (log/error "failed loading messages" current-chat-id err)
  (when current-chat-id
    {:db (assoc-in db [:pagination-info current-chat-id :loading-messages?] false)}))

(fx/defn messages-loaded
  "Loads more messages for current chat"
  {:events [::messages-loaded]}
  [{{:keys [current-chat-id] :as db} :db :as cofx}
   chat-id
   session-id
   {:keys [cursor messages]}]
  (when-not (or (nil? current-chat-id)
                (not= chat-id current-chat-id)
                (and (get-in db [:pagination-info current-chat-id :messages-initialized?])
                     (not= session-id
                           (get-in db [:pagination-info current-chat-id :messages-initialized?]))))
    (let [already-loaded-messages      (get-in db [:messages current-chat-id])
          loaded-unviewed-messages-ids (get-in db [:chats current-chat-id :loaded-unviewed-messages-ids] #{})
          users                        (get-in db [:chats current-chat-id :users] {})
          ;; We remove those messages that are already loaded, as we might get some duplicates
          {:keys [all-messages
                  new-messages
                  last-clock-value
                  unviewed-message-ids
                  users]}
          (reduce (fn [{:keys [last-clock-value all-messages users] :as acc}
                       {:keys [clock-value seen message-id alias name identicon from]
                        :as message}]
                    (let [nickname (get-in db [:contacts/contacts from :nickname])]
                      (cond-> acc
                        (and alias (not= alias ""))
                        (update :users assoc from
                                (mentions/add-searchable-phrases
                                 {:alias      alias
                                  :name       (or name alias)
                                  :identicon  identicon
                                  :public-key from
                                  :nickname   nickname}))
                        (or (nil? last-clock-value)
                            (> last-clock-value clock-value))
                        (assoc :last-clock-value clock-value)

                        (not seen)
                        (update :unviewed-message-ids conj message-id)

                        (nil? (get all-messages message-id))
                        (update :new-messages conj message)

                        :always
                        (update :all-messages assoc message-id message))))
                  {:all-messages         already-loaded-messages
                   :unviewed-message-ids loaded-unviewed-messages-ids
                   :users                users
                   :new-messages         []}
                  messages)]
      ;(println "MESSAGES LOADED" (count messages) (count already-loaded-messages) " ALL:" (count all-messages))
      (fx/merge cofx
                {:db (-> db
                         (assoc-in [:pagination-info current-chat-id :cursor-clock-value] (when (seq cursor) (cursor->clock-value cursor)))
                         (assoc-in [:chats current-chat-id :loaded-unviewed-messages-ids] unviewed-message-ids)
                         (assoc-in [:chats current-chat-id :users] users)
                         (assoc-in [:pagination-info current-chat-id :loading-messages?] false)
                         ;(assoc-in [:pagination-info current-chat-id :from] (max (- (count all-messages) 80) 0))
                         ;(assoc-in [:pagination-info current-chat-id :to] (count all-messages))
                         (assoc-in [:messages current-chat-id] all-messages)
                         (update-in [:message-lists current-chat-id] message-list/add-many new-messages)
                         (assoc-in [:pagination-info current-chat-id :cursor] cursor)
                         (assoc-in [:pagination-info current-chat-id :all-loaded?]
                                   (empty? cursor)))}
                #_(message-seen/mark-messages-seen current-chat-id)))))

(fx/defn load-more-messages
  [{:keys [db] :as cofx}]
  (when-let [current-chat-id (:current-chat-id db)]
    (when-let [session-id (get-in db [:pagination-info current-chat-id :messages-initialized?])]
      (when-not (or (get-in db [:pagination-info current-chat-id :all-loaded?])
                    (get-in db [:pagination-info current-chat-id :loading-messages?]))
        (let [cursor (get-in db [:pagination-info current-chat-id :cursor])
              load-messages-fx (data-store.messages/messages-by-chat-id-rpc
                                current-chat-id
                                cursor
                                constants/default-number-of-messages
                                #(re-frame/dispatch [::messages-loaded current-chat-id session-id %])
                                #(re-frame/dispatch [::failed-loading-messages current-chat-id session-id %]))]
          ;(println "LOAD MORE MESSAGES" (count (get-in db [:messages current-chat-id])) "CURS" cursor)
          (fx/merge cofx
                    load-messages-fx
                    (reactions/load-more-reactions cursor)
                    (mailserver/load-gaps-fx current-chat-id)))))))

(fx/defn load-more-messages-to-beggining
  [{:keys [db] :as cofx}]
  (when-let [current-chat-id (:current-chat-id db)]
    {:db (update-in db [:pagination-info current-chat-id :from] #(max (- % 20) 0))}))

(fx/defn visible-element-changed
  {:events [:chat.ui/visible-element-changed]}
  [{:keys [db] :as cofx} indx]
  (when-let [current-chat-id (:current-chat-id db)]
    {:db (assoc-in db [:pagination-info current-chat-id :last-visible] indx)}))



(fx/defn load-messages
  {:events [:load-messages]}
  [{:keys [db now] :as cofx}]
  (when-let [current-chat-id (:current-chat-id db)]
    ;(println "INIT CHAT load messages" current-chat-id (get-in db [:pagination-info current-chat-id :messages-initialized?]))
    (if (get-in db [:pagination-info current-chat-id :messages-initialized?])
      ;; We mark messages as seen in case we received them while on a different tab
      #_(message-seen/mark-messages-seen cofx current-chat-id)
      nil
      (do
       ; reset chat first-not-visible-items state
        (chat.state/reset)
        (fx/merge cofx
                  {:db (assoc-in db [:pagination-info current-chat-id :messages-initialized?] now)}
                  #_(message-seen/mark-messages-seen current-chat-id)
                  (load-more-messages))))))
