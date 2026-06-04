"""add_data_quality_constraints

Revision ID: 614f9e81193b
Revises: 631e4c40102e
Create Date: 2026-06-04 13:29:20.366318

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '614f9e81193b'
down_revision: Union[str, Sequence[str], None] = '631e4c40102e'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # --- Check and Unique Constraints ---
    op.create_unique_constraint('uq_user_preference_key', 'user_preferences', ['user_id', 'preference_key'])
    op.create_check_constraint('chk_connection_status', 'connections', "status IN ('pending', 'accepted', 'blocked')")
    op.create_check_constraint('chk_agent_chat_role', 'agent_chat_history', "role IN ('user', 'assistant', 'system')")
    op.create_check_constraint('chk_scraped_opportunity_status', 'scraped_opportunities', "status IN ('Active', 'Inactive')")
    op.create_check_constraint('chk_user_settings_theme', 'user_settings', "theme IN ('dark', 'light', 'system')")
    op.create_check_constraint('chk_user_settings_visibility', 'user_settings', "profile_visibility IN ('public', 'connections', 'private')")

    # --- Column Length limits ---
    # users
    op.alter_column('users', 'id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('users', 'openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('users', 'display_name', type_=sa.String(255), existing_type=sa.String())
    
    # user_preferences
    op.alter_column('user_preferences', 'user_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('user_preferences', 'preference_key', type_=sa.String(255), existing_type=sa.String())
    
    # connections
    op.alter_column('connections', 'user_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('connections', 'connected_user_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('connections', 'status', type_=sa.String(50), existing_type=sa.String())
    
    # messages
    op.alter_column('messages', 'sender_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('messages', 'receiver_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('messages', 'content', type_=sa.String(4000), existing_type=sa.String())
    
    # agent_chat_history
    op.alter_column('agent_chat_history', 'user_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('agent_chat_history', 'context_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('agent_chat_history', 'role', type_=sa.String(50), existing_type=sa.String())
    op.alter_column('agent_chat_history', 'content', type_=sa.String(4000), existing_type=sa.String())
    
    # cache_entries
    op.alter_column('cache_entries', 'cache_key', type_=sa.String(512), existing_type=sa.String())
    
    # researcher_profiles
    op.alter_column('researcher_profiles', 'openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('researcher_profiles', 'display_name', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('researcher_profiles', 'institution', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('researcher_profiles', 'field_of_study', type_=sa.String(255), existing_type=sa.String())
    
    # researcher_connections
    op.alter_column('researcher_connections', 'author_openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('researcher_connections', 'connection_openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('researcher_connections', 'connection_name', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('researcher_connections', 'connection_institution', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('researcher_connections', 'connection_field', type_=sa.String(255), existing_type=sa.String())
    
    # researcher_works
    op.alter_column('researcher_works', 'author_openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('researcher_works', 'work_openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('researcher_works', 'doi', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('researcher_works', 'journal', type_=sa.String(255), existing_type=sa.String())
    
    # researcher_metrics
    op.alter_column('researcher_metrics', 'openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('researcher_metrics', 'display_name', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('researcher_metrics', 'orcid', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('researcher_metrics', 'current_institution', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('researcher_metrics', 'field_of_study', type_=sa.String(255), existing_type=sa.String())
    
    # daily_feed_items
    op.alter_column('daily_feed_items', 'author_openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('daily_feed_items', 'work_openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('daily_feed_items', 'journal', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('daily_feed_items', 'doi', type_=sa.String(255), existing_type=sa.String())
    
    # conjectures
    op.alter_column('conjectures', 'author_openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('conjectures', 'category', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('conjectures', 'title', type_=sa.String(255), existing_type=sa.String())
    
    # scraped_opportunities
    op.alter_column('scraped_opportunities', 'id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('scraped_opportunities', 'type', type_=sa.String(50), existing_type=sa.String())
    op.alter_column('scraped_opportunities', 'title', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('scraped_opportunities', 'company_or_funder', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('scraped_opportunities', 'url', type_=sa.String(512), existing_type=sa.String())
    op.alter_column('scraped_opportunities', 'posted_ago', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('scraped_opportunities', 'amount', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('scraped_opportunities', 'deadline', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('scraped_opportunities', 'status', type_=sa.String(50), existing_type=sa.String())
    op.alter_column('scraped_opportunities', 'focus_topic', type_=sa.String(100), existing_type=sa.String())
    
    # user_settings
    op.alter_column('user_settings', 'user_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('user_settings', 'theme', type_=sa.String(50), existing_type=sa.String())
    op.alter_column('user_settings', 'accent_color', type_=sa.String(50), existing_type=sa.String())
    op.alter_column('user_settings', 'profile_visibility', type_=sa.String(50), existing_type=sa.String())
    
    # user_activity_log
    op.alter_column('user_activity_log', 'user_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('user_activity_log', 'event_type', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('user_activity_log', 'entity_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('user_activity_log', 'entity_name', type_=sa.String(255), existing_type=sa.String())
    
    # api_request_log
    op.alter_column('api_request_log', 'endpoint', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('api_request_log', 'method', type_=sa.String(10), existing_type=sa.String())
    op.alter_column('api_request_log', 'user_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('api_request_log', 'author_id', type_=sa.String(100), existing_type=sa.String())
    
    # author_search_log
    op.alter_column('author_search_log', 'openalex_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('author_search_log', 'display_name', type_=sa.String(255), existing_type=sa.String())
    
    # agent_history_summaries
    op.alter_column('agent_history_summaries', 'cache_key', type_=sa.String(256), existing_type=sa.String())
    op.alter_column('agent_history_summaries', 'user_id', type_=sa.String(100), existing_type=sa.String())
    
    # agent_document_uploads
    op.alter_column('agent_document_uploads', 'user_id', type_=sa.String(100), existing_type=sa.String())
    op.alter_column('agent_document_uploads', 'filename', type_=sa.String(255), existing_type=sa.String())
    op.alter_column('agent_document_uploads', 'content_type', type_=sa.String(100), existing_type=sa.String())


def downgrade() -> None:
    """Downgrade schema."""
    # --- Column Length limits restore to default String ---
    op.alter_column('agent_document_uploads', 'content_type', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('agent_document_uploads', 'filename', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('agent_document_uploads', 'user_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('agent_history_summaries', 'user_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('agent_history_summaries', 'cache_key', type_=sa.String(), existing_type=sa.String(256))
    
    op.alter_column('author_search_log', 'display_name', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('author_search_log', 'openalex_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('api_request_log', 'author_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('api_request_log', 'user_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('api_request_log', 'method', type_=sa.String(), existing_type=sa.String(10))
    op.alter_column('api_request_log', 'endpoint', type_=sa.String(), existing_type=sa.String(255))
    
    op.alter_column('user_activity_log', 'entity_name', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('user_activity_log', 'entity_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('user_activity_log', 'event_type', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('user_activity_log', 'user_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('user_settings', 'profile_visibility', type_=sa.String(), existing_type=sa.String(50))
    op.alter_column('user_settings', 'accent_color', type_=sa.String(), existing_type=sa.String(50))
    op.alter_column('user_settings', 'theme', type_=sa.String(), existing_type=sa.String(50))
    op.alter_column('user_settings', 'user_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('scraped_opportunities', 'focus_topic', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('scraped_opportunities', 'status', type_=sa.String(), existing_type=sa.String(50))
    op.alter_column('scraped_opportunities', 'deadline', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('scraped_opportunities', 'amount', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('scraped_opportunities', 'posted_ago', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('scraped_opportunities', 'url', type_=sa.String(), existing_type=sa.String(512))
    op.alter_column('scraped_opportunities', 'company_or_funder', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('scraped_opportunities', 'title', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('scraped_opportunities', 'type', type_=sa.String(), existing_type=sa.String(50))
    op.alter_column('scraped_opportunities', 'id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('conjectures', 'title', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('conjectures', 'category', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('conjectures', 'author_openalex_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('daily_feed_items', 'doi', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('daily_feed_items', 'journal', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('daily_feed_items', 'work_openalex_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('daily_feed_items', 'author_openalex_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('researcher_metrics', 'field_of_study', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_metrics', 'current_institution', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_metrics', 'orcid', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('researcher_metrics', 'display_name', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_metrics', 'openalex_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('researcher_works', 'journal', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_works', 'doi', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_works', 'work_openalex_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('researcher_works', 'author_openalex_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('researcher_connections', 'connection_field', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_connections', 'connection_institution', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_connections', 'connection_name', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_connections', 'connection_openalex_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('researcher_connections', 'author_openalex_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('researcher_profiles', 'field_of_study', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_profiles', 'institution', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_profiles', 'display_name', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('researcher_profiles', 'openalex_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('cache_entries', 'cache_key', type_=sa.String(), existing_type=sa.String(512))
    
    op.alter_column('agent_chat_history', 'content', type_=sa.String(), existing_type=sa.String(4000))
    op.alter_column('agent_chat_history', 'role', type_=sa.String(), existing_type=sa.String(50))
    op.alter_column('agent_chat_history', 'context_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('agent_chat_history', 'user_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('messages', 'content', type_=sa.String(), existing_type=sa.String(4000))
    op.alter_column('messages', 'receiver_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('messages', 'sender_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('connections', 'status', type_=sa.String(), existing_type=sa.String(50))
    op.alter_column('connections', 'connected_user_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('connections', 'user_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('user_preferences', 'preference_key', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('user_preferences', 'user_id', type_=sa.String(), existing_type=sa.String(100))
    
    op.alter_column('users', 'display_name', type_=sa.String(), existing_type=sa.String(255))
    op.alter_column('users', 'openalex_id', type_=sa.String(), existing_type=sa.String(100))
    op.alter_column('users', 'id', type_=sa.String(), existing_type=sa.String(100))

    # --- Constraints Drop ---
    op.drop_constraint('chk_user_settings_visibility', 'user_settings', type_='check')
    op.drop_constraint('chk_user_settings_theme', 'user_settings', type_='check')
    op.drop_constraint('chk_scraped_opportunity_status', 'scraped_opportunities', type_='check')
    op.drop_constraint('chk_agent_chat_role', 'agent_chat_history', type_='check')
    op.drop_constraint('chk_connection_status', 'connections', type_='check')
    op.drop_constraint('uq_user_preference_key', 'user_preferences', type_='unique')
