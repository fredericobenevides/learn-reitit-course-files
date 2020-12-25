(ns cheffy.conversation.db
  (:require [next.jdbc.sql :as sql]
            [next.jdbc :as jdbc])
  (:import (java.util UUID)))

(defmulti dispatch (fn [[name _db _data]] name))

(defmethod dispatch :find-conversation-by-uid
  [[_ db {:keys [uid]}]]
  (with-open [conn (jdbc/get-connection db)]
    (let [conn-opts (jdbc/with-options conn (:options db))
          conversations (sql/find-by-keys conn-opts :conversation {:uid uid})]
      (doall
        (for [{:conversation/keys [conversation-id] :as conversation} conversations
              :let [{:message/keys [created-at]}
                    (jdbc/execute-one! conn-opts ["SELECT created_at FROM message
                                                   WHERE conversation_id = ?
                                                   ORDER BY created_at DESC
                                                   LIMIT 1" conversation-id])
                    with
                    (jdbc/execute-one! conn-opts ["SELECT uid FROM conversation
                                                   WHERE uid != ? AND conversation_id = ?" uid conversation-id])
                    [{:account/keys [name picture]}] (sql/find-by-keys conn-opts :account with)]]
          (assoc conversation
            :conversation/updated-at created-at
            :conversation/with-name name
            :conversation/with-picture picture))))))

(defmethod dispatch :find-messages-by-conversation
  [[_ db conversation-id]]
  (sql/find-by-keys db :message conversation-id))

(defmethod dispatch :insert-message
  [[_ db {:keys [conversation-id from to] :as data}]]
  (jdbc/with-transaction [tx db]
    (sql/insert! tx :message
      (-> data (assoc :uid from) (dissoc :from :to))
      (:options db))
    (jdbc/execute-one! tx ["UPDATE conversation
                            SET notifications = notifications + 1
                            WHERE conversation_id = ?
                            AND uid = ?" conversation-id to])))

(defmethod dispatch :start-conversation
  [[_ db {:keys [to from] :as data}]]
  (with-open [conn (jdbc/get-connection db)]
    (let [conn-opts (jdbc/with-options conn (:options db))
          conversing (jdbc/execute-one! conn-opts ["SELECT a.conversation_id
                                                    FROM conversation a
                                                    JOIN conversation b
                                                    ON a.conversation_id = b.conversation_id
                                                    WHERE a.uid = ? AND b.uid = ?" to from])]
      (if-let [conversation-id (:conversation/conversation_id conversing)]
        (dispatch [:insert-message conn-opts (assoc data :conversation_id conversation-id)])
        (jdbc/with-transaction [tx conn-opts]
          (let [conversation-id (str (UUID/randomUUID))]
            (sql/insert! tx :message (-> data (assoc :uid from) (dissoc :to :from)))
            (sql/insert-multi! tx :conversation
              [:notifcations :uid :conversation_id]
              [[1 to conversation-id]
               [0 from conversation-id]])
            conversation-id))))))

(defmethod dispatch :clear-notifications
  [[_ db data]]
  (-> (sql/update! db :conversation {:notifications 0} data)
    :next.jdbc/update-count
    (pos?)))

(comment
  (dispatch [:find-conversation-by-uid {} {}]))