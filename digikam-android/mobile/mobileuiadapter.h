/*
 * Android-only adaptive UI layer for the downstream digiKam mobile port.
 *
 * Keeps digiKam's models, database, image pipeline and views intact while
 * replacing desktop window chrome with a touch-first phone/tablet shell.
 */
#pragma once

#include <QObject>

class QApplication;
class QMainWindow;
class QString;
class QToolBar;
class QWidget;

namespace Digikam
{

class MobileUiAdapter final : public QObject
{
    Q_OBJECT

public:
    static void prepareApplication(QApplication* app);
    static MobileUiAdapter* install(QMainWindow* window);

protected:
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    explicit MobileUiAdapter(QMainWindow* window);

    void applyWindowChrome();
    void applyTouchBehaviour();
    void buildBottomNavigation();
    void collapseSidebars();
    bool activateSidebar(const QString& objectName);
    void showMoreMenu(QWidget* anchor);
    void resizeActiveDrawer();

private:
    QMainWindow* m_window = nullptr;
    QToolBar*    m_bottomBar = nullptr;
};

} // namespace Digikam
