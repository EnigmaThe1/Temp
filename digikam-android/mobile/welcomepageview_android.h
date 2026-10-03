// Android compatibility replacement for digiKam's embedded WebEngine welcome page.
#pragma once

#include <QTextBrowser>
#include <QUrl>

namespace Digikam
{

class WelcomePageView : public QTextBrowser
{
    Q_OBJECT

public:

    explicit WelcomePageView(QWidget* const parent);
    ~WelcomePageView() override = default;

private Q_SLOTS:

    void slotUrlOpen(const QUrl& url);
    void slotThemeChanged();
};

} // namespace Digikam
