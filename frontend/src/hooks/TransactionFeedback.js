import { createContext, useContext } from 'react'

const TransactionFeedbackContext = createContext(null)
export const TransactionFeedbackProvider = TransactionFeedbackContext.Provider
export function useTransactionFeedback() { return useContext(TransactionFeedbackContext) }
