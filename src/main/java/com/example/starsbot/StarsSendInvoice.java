package com.example.starsbot;

import org.apache.commons.lang3.StringUtils;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.exceptions.TelegramApiValidationException;

import java.util.List;

/**
 * Workaround for telegrambots 6.9.7.1 validation:
 * it requires providerToken for all currencies, while XTR (Telegram Stars)
 * must be sent without a payment provider token.
 */
public class StarsSendInvoice extends SendInvoice {
    private static final String STARS_CURRENCY = "XTR";

    @Override
    public void validate() throws TelegramApiValidationException {
        if (StringUtils.isEmpty(getChatId())) {
            throw new TelegramApiValidationException("ChatId parameter can't be empty", this);
        }
        if (StringUtils.isEmpty(getTitle()) || getTitle().length() > 32) {
            throw new TelegramApiValidationException("Title parameter can't be empty or longer than 32 chars", this);
        }
        if (StringUtils.isEmpty(getDescription()) || getDescription().length() > 255) {
            throw new TelegramApiValidationException("Description parameter can't be empty or longer than 255 chars", this);
        }
        if (StringUtils.isEmpty(getPayload())) {
            throw new TelegramApiValidationException("Payload parameter can't be empty", this);
        }
        if (StringUtils.isEmpty(getCurrency())) {
            throw new TelegramApiValidationException("Currency parameter can't be empty", this);
        }

        boolean starsInvoice = STARS_CURRENCY.equalsIgnoreCase(getCurrency());
        if (!starsInvoice && StringUtils.isEmpty(getProviderToken())) {
            throw new TelegramApiValidationException("ProviderToken parameter can't be empty", this);
        }

        List<LabeledPrice> prices = getPrices();
        if (prices == null || prices.isEmpty()) {
            throw new TelegramApiValidationException("Prices parameter can't be empty", this);
        }
        for (LabeledPrice price : prices) {
            price.validate();
        }

        if (getSuggestedTipAmounts() != null && !getSuggestedTipAmounts().isEmpty() && getSuggestedTipAmounts().size() > 4) {
            throw new TelegramApiValidationException("No more that 4 suggested tips allowed", this);
        }
        if (getReplyMarkup() != null) {
            getReplyMarkup().validate();
        }
        if (getReplyParameters() != null) {
            getReplyParameters().validate();
        }
    }
}

